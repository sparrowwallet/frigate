package com.sparrowwallet.frigate.electrum;

import com.fasterxml.jackson.databind.JsonNode;
import com.sparrowwallet.frigate.Frigate;
import com.sparrowwallet.frigate.io.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

import static com.sparrowwallet.frigate.electrum.FakeElectrumBackend.await;
import static org.junit.jupiter.api.Assertions.*;

public class AdminServerTest {
    private FakeElectrumBackend backend;
    private ElectrumServerRunnable server;
    private final List<TestElectrumClient> clients = new ArrayList<>();

    @BeforeEach
    public void setUp() throws IOException {
        backend = new FakeElectrumBackend();
        Config config = new Config();
        config.getServer().setBackendElectrumServer(backend.getUrl());
        config.getServer().setAdminPort(freePort());
        Config.setInstance(config);
        server = startServer();
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

    private static int freePort() throws IOException {
        try(ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static ElectrumServerRunnable startServer() {
        ElectrumServerRunnable runnable = new ElectrumServerRunnable(null, null, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, null);
        Thread.ofVirtual().name("TestElectrumServer").start(runnable);
        return runnable;
    }

    private JsonNode getInfo() throws Exception {
        try(TestElectrumClient admin = new TestElectrumClient(server.getAdminLocalPort())) {
            return admin.request("getinfo");
        }
    }

    private void connectWalletsSubscribing(int wallets) throws Exception {
        for(int i = 0; i < wallets; i++) {
            TestElectrumClient client = new TestElectrumClient(server.getTcpLocalPort());
            clients.add(client);
            client.request("server.version", "TestWallet", "1.4");
            client.request("blockchain.scripthash.subscribe", String.format("%064x", i + 1));
        }
    }

    @Test
    public void getInfoReportsServerAndHealth() throws Exception {
        connectWalletsSubscribing(2);
        await(() -> server.getStats().backendConnected() == 2, "backend connections");

        JsonNode info = getInfo().path("result");

        assertEquals(Frigate.SERVER_VERSION, info.path("version").asText());
        assertFalse(info.path("network").asText().isEmpty());
        assertTrue(info.path("uptimeSeconds").asLong() >= 0);
        assertEquals("connected", info.path("health").path("backend").asText());
        assertEquals(Config.LimitsConfig.DEFAULT_MAX_CONNECTIONS, info.path("limits").path("maxConnections").asInt());
        //no ssl:// backend and no Bitcoin Core
        assertTrue(info.path("backendTls").isMissingNode());
        assertTrue(info.path("health").path("tipHeight").isMissingNode());
    }

    @Test
    public void usageIsRoundedAndSmallCountsOmitted() throws Exception {
        connectWalletsSubscribing(2);
        await(() -> server.getStats().scriptHashSubscriptions() == 2, "subscriptions");
        //fewer than ten of everything: no usage figures at all
        JsonNode usage = getInfo().path("result").path("usage");
        assertEquals(0, usage.size(), usage.toString());

        connectWalletsSubscribing(12);
        await(() -> server.getStats().scriptHashSubscriptions() == 14, "subscriptions");
        usage = getInfo().path("result").path("usage");
        assertEquals(10, usage.path("sessions").asLong());
        assertEquals(10, usage.path("scriptHashSubscriptions").asLong());
        //one IP, below the threshold
        assertTrue(usage.path("ips").isMissingNode());
    }

    @Test
    public void offersNothingPerSession() throws Exception {
        try(TestElectrumClient admin = new TestElectrumClient(server.getAdminLocalPort())) {
            assertEquals(-32601, admin.request("sessions").path("error").path("code").asInt());
        }
    }

    @Test
    public void bindsToLoopbackOnly() {
        assertTrue(server.getAdminServer().getInetAddress().isLoopbackAddress());
    }

    @Test
    public void disabledByDefault() throws Exception {
        server.stop();
        Config.get().getServer().setAdminPort(null);
        server = startServer();

        assertEquals(-1, server.getAdminLocalPort());
        assertEquals(0, new Config().getServer().getAdminPort());
    }
}
