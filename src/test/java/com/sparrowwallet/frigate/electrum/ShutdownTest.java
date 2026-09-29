package com.sparrowwallet.frigate.electrum;

import com.fasterxml.jackson.databind.JsonNode;
import com.sparrowwallet.frigate.io.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.sparrowwallet.frigate.electrum.FakeElectrumBackend.await;
import static org.junit.jupiter.api.Assertions.*;

public class ShutdownTest {
    private FakeElectrumBackend backend;
    private ElectrumServerRunnable server;
    private final List<TestElectrumClient> clients = new ArrayList<>();

    @BeforeEach
    public void setUp() throws IOException {
        backend = new FakeElectrumBackend();
        Config config = new Config();
        config.getServer().setBackendElectrumServer(backend.getUrl());
        config.getServer().setBackendRequestTimeoutSeconds(30);
        Config.setInstance(config);
        server = new ElectrumServerRunnable(null, null, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, null);
        Thread.ofVirtual().name("TestElectrumServer").start(server);
    }

    @AfterEach
    public void tearDown() throws IOException {
        for(TestElectrumClient client : clients) {
            client.close();
        }
        server.stop();
        backend.close();
        Config.setInstance(null);
    }

    private TestElectrumClient connectClient(int index) throws Exception {
        TestElectrumClient client = new TestElectrumClient(server.getTcpLocalPort());
        clients.add(client);
        client.request("server.version", "TestWallet", "1.4");
        client.request("blockchain.scripthash.subscribe", scriptHash(index));
        return client;
    }

    private static String scriptHash(int i) {
        return String.format("%064x", i + 1);
    }

    /** Shuts the server down on another thread, completing with how long it took. */
    private CompletableFuture<Duration> shutdownAsync(Duration drain) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            server.shutdown(drain);
            return Duration.ofNanos(System.nanoTime() - start);
        });
    }

    @Test
    public void idleSessionsCloseAtOnceWithTheirBackendConnections() throws Exception {
        TestElectrumClient first = connectClient(0);
        TestElectrumClient second = connectClient(1);
        await(() -> backend.getOpenConnections().size() == 2, "backend connections");

        Duration took = shutdownAsync(Duration.ofSeconds(10)).get(5, TimeUnit.SECONDS);

        assertTrue(took.toMillis() < 2000, "took " + took.toMillis() + "ms");
        assertTrue(first.awaitDisconnect(5, TimeUnit.SECONDS));
        assertTrue(second.awaitDisconnect(5, TimeUnit.SECONDS));
        assertTrue(server.getSessions().isEmpty());
        await(() -> backend.getOpenConnections().isEmpty(), "backend connections to close");
    }

    @Test
    public void requestInProgressCompletesBeforeTheSessionCloses() throws Exception {
        TestElectrumClient client = connectClient(0);
        backend.setResponseDelay("blockchain.scripthash.get_history", 1000);
        int id = client.send("blockchain.scripthash.get_history", scriptHash(0));
        Thread.sleep(200);

        CompletableFuture<Duration> shutdown = shutdownAsync(Duration.ofSeconds(10));

        JsonNode response = client.awaitResponse(id);
        assertTrue(response.path("result").isArray(), response.toString());
        assertTrue(client.awaitDisconnect(5, TimeUnit.SECONDS));
        Duration took = shutdown.get(5, TimeUnit.SECONDS);
        assertTrue(took.toMillis() >= 500 && took.toMillis() < 5000, "took " + took.toMillis() + "ms");
        await(() -> backend.getOpenConnections().isEmpty(), "backend connection to close");
    }

    @Test
    public void requestOutlastingTheDrainIsCutOff() throws Exception {
        TestElectrumClient client = connectClient(0);
        backend.setResponseDelay("blockchain.scripthash.get_history", 20_000);
        client.send("blockchain.scripthash.get_history", scriptHash(0));
        Thread.sleep(200);

        Duration took = shutdownAsync(Duration.ofSeconds(1)).get(10, TimeUnit.SECONDS);

        assertTrue(took.toMillis() >= 900 && took.toMillis() < 4000, "took " + took.toMillis() + "ms");
        assertTrue(client.awaitDisconnect(5, TimeUnit.SECONDS));
        assertNull(client.pollResponse(0, TimeUnit.MILLISECONDS));
        assertTrue(server.getSessions().isEmpty());
    }

    @Test
    public void interruptDoesNotCutShutdownShort() throws Exception {
        TestElectrumClient client = connectClient(0);
        backend.setResponseDelay("blockchain.scripthash.get_history", 20_000);
        client.send("blockchain.scripthash.get_history", scriptHash(0));
        Thread.sleep(200);

        CompletableFuture<Boolean> interruptedAfter = new CompletableFuture<>();
        Thread shutdownThread = Thread.ofPlatform().start(() -> {
            server.shutdown(Duration.ofSeconds(2));
            interruptedAfter.complete(Thread.currentThread().isInterrupted());
        });
        Thread.sleep(500);
        shutdownThread.interrupt();

        //the drain and the wait after the forced close still run, so the session has ended, and the interrupt is kept for the caller
        assertTrue(interruptedAfter.get(10, TimeUnit.SECONDS));
        assertTrue(server.getSessions().isEmpty());
        assertTrue(client.awaitDisconnect(5, TimeUnit.SECONDS));
    }

    @Test
    public void newConnectionsAreRefused() throws Exception {
        connectClient(0);
        int port = server.getTcpLocalPort();

        shutdownAsync(Duration.ofSeconds(10)).get(5, TimeUnit.SECONDS);

        assertThrows(ConnectException.class, () -> {
            try(Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000);
            }
        });
    }

    @Test
    public void shutdownWithoutSessionsReturnsAtOnce() throws Exception {
        Duration took = shutdownAsync(Duration.ofSeconds(10)).get(5, TimeUnit.SECONDS);
        assertTrue(took.toMillis() < 1000, "took " + took.toMillis() + "ms");
    }
}
