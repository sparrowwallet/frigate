package com.sparrowwallet.frigate.electrum;

import com.fasterxml.jackson.databind.JsonNode;
import com.sparrowwallet.frigate.io.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static com.sparrowwallet.frigate.electrum.FakeElectrumBackend.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs a Frigate Electrum server against a fake backend, with test clients standing in for wallets, to check notification
 * routing and backend resilience end to end.
 */
public class ElectrumSessionIntegrationTest {
    private FakeElectrumBackend backend;
    private ElectrumServerRunnable server;
    private final List<TestElectrumClient> clients = new ArrayList<>();

    @BeforeEach
    public void setUp() throws IOException {
        backend = new FakeElectrumBackend();
        Config config = new Config();
        config.getServer().setBackendElectrumServer(backend.getUrl());
        config.getServer().setBackendRequestTimeoutSeconds(5);
        config.getServer().setBackendReconnectMaxBackoffSeconds(1);
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

    private TestElectrumClient connectClient() throws Exception {
        return connectClient(0);
    }

    private TestElectrumClient connectClient(int receiveBufferSize) throws Exception {
        TestElectrumClient client = new TestElectrumClient(new Socket(), server.getTcpLocalPort(), receiveBufferSize);
        clients.add(client);
        JsonNode version = client.request("server.version", "TestWallet", "1.4");
        assertEquals("1.4", version.path("result").path(1).asText());
        return client;
    }

    private static void subscribe(TestElectrumClient client, String scriptHash) throws Exception {
        JsonNode response = client.request("blockchain.scripthash.subscribe", scriptHash);
        assertFalse(response.has("error") && !response.get("error").isNull(), "subscribe failed: " + response);
    }

    private static void assertNotified(TestElectrumClient client, String scriptHash, String status) throws InterruptedException {
        JsonNode notification = client.pollNotification(10, TimeUnit.SECONDS);
        assertNotNull(notification, "no notification for " + scriptHash);
        assertEquals("blockchain.scripthash.subscribe", notification.path("method").asText());
        assertEquals(scriptHash, TestElectrumClient.param(notification, 0, "scripthash").asText());
        JsonNode notifiedStatus = TestElectrumClient.param(notification, 1, "status");
        assertEquals(status, notifiedStatus.isNull() || notifiedStatus.isMissingNode() ? null : notifiedStatus.asText());
    }

    private static void assertNoNotification(TestElectrumClient client, long millis) throws InterruptedException {
        JsonNode notification = client.pollNotification(millis, TimeUnit.MILLISECONDS);
        assertNull(notification, "unexpected notification " + notification);
    }

    private static int errorCode(JsonNode response) {
        return response.path("error").path("code").asInt();
    }

    private static String scriptHash(int i) {
        return String.format("%064x", i + 1);
    }

    private static String status(int i) {
        return String.format("%064x", 0xabc000 + i);
    }

    @Test
    public void notificationsReachOnlyTheOwningSession() throws Exception {
        List<TestElectrumClient> sessions = new ArrayList<>();
        for(int i = 0; i < 3; i++) {
            TestElectrumClient client = connectClient();
            subscribe(client, scriptHash(i));
            sessions.add(client);
        }

        //one backend connection per session, each carrying only its own session's subscription
        assertEquals(3, backend.getOpenConnections().size());
        for(int i = 0; i < 3; i++) {
            assertEquals(Set.of(scriptHash(i)), backend.getSubscribedConnections(scriptHash(i)).getFirst().getSubscriptions());
        }

        for(int i = 0; i < 3; i++) {
            backend.setStatus(scriptHash(i), status(i), true);
            assertNotified(sessions.get(i), scriptHash(i), status(i));
        }
        for(TestElectrumClient client : sessions) {
            assertNoNotification(client, 200);
        }
    }

    @Test
    public void backendRestartRestoresEachSessionAndDeliversMissedChanges() throws Exception {
        List<TestElectrumClient> sessions = new ArrayList<>();
        for(int i = 0; i < 3; i++) {
            backend.setStatus(scriptHash(i), status(i), false);
            TestElectrumClient client = connectClient();
            subscribe(client, scriptHash(i));
            sessions.add(client);
        }

        //the backend goes down; two of the three scripthashes change while it is down
        backend.setAccepting(false);
        backend.dropAllConnections();
        backend.setStatus(scriptHash(0), status(100), false);
        backend.setStatus(scriptHash(1), null, false);
        Thread.sleep(300);
        backend.setAccepting(true);

        assertNotified(sessions.get(0), scriptHash(0), status(100));
        assertNotified(sessions.get(1), scriptHash(1), null);
        assertNoNotification(sessions.get(2), 500);

        //each session reconnected and resubscribed only its own scripthash, and its client stayed connected
        await(() -> backend.getOpenConnections().size() == 3, "all sessions reconnected");
        for(int i = 0; i < 3; i++) {
            List<FakeElectrumBackend.Connection> subscribed = backend.getSubscribedConnections(scriptHash(i));
            assertEquals(1, subscribed.size());
            assertEquals(Set.of(scriptHash(i)), subscribed.getFirst().getSubscriptions());
            assertTrue(sessions.get(i).request("blockchain.scripthash.get_history", scriptHash(i)).path("result").isArray());
        }

        //notifications flow again on the new connections
        backend.setStatus(scriptHash(2), status(200), true);
        assertNotified(sessions.get(2), scriptHash(2), status(200));
    }

    @Test
    public void clientDisconnectClosesItsBackendConnection() throws Exception {
        TestElectrumClient first = connectClient();
        subscribe(first, scriptHash(0));
        TestElectrumClient second = connectClient();
        subscribe(second, scriptHash(1));
        FakeElectrumBackend.Connection firstBackend = backend.getSubscribedConnections(scriptHash(0)).getFirst();

        first.close();

        await(() -> !firstBackend.isOpen(), "backend connection of disconnected client to close");
        assertEquals(1, backend.getOpenConnections().size());
        assertEquals(1, backend.getSubscribedConnections(scriptHash(1)).size());
    }

    @Test
    public void outageFailsProxiedRequestsButKeepsSubscriptions() throws Exception {
        backend.setStatus(scriptHash(0), status(0), false);
        TestElectrumClient client = connectClient();
        subscribe(client, scriptHash(0));

        backend.setAccepting(false);
        backend.dropAllConnections();
        await(() -> backend.getOpenConnections().isEmpty(), "backend connections to drop");

        //proxied requests fail with the backend unavailable error while the client stays connected
        JsonNode history = client.request("blockchain.scripthash.get_history", scriptHash(0));
        assertEquals(-32000, errorCode(history), history.toString());

        //a resubscribe during the outage fails, but must not remove the existing subscription
        JsonNode resubscribe = client.request("blockchain.scripthash.subscribe", scriptHash(0));
        assertEquals(-32000, errorCode(resubscribe), resubscribe.toString());

        backend.setStatus(scriptHash(0), status(1), false);
        backend.setAccepting(true);

        //the subscription was kept, so the change missed during the outage is delivered after the reconnect
        assertNotified(client, scriptHash(0), status(1));
        assertTrue(client.request("blockchain.scripthash.get_history", scriptHash(0)).path("result").isArray());
    }

    @Test
    public void notificationAfterUnsubscribeIsNotDelivered() throws Exception {
        TestElectrumClient client = connectClient();
        subscribe(client, scriptHash(0));
        subscribe(client, scriptHash(1));

        JsonNode unsubscribe = client.request("blockchain.scripthash.unsubscribe", scriptHash(0));
        assertTrue(unsubscribe.path("result").asBoolean());

        //a backend that still notifies the unsubscribed scripthash is ignored
        backend.notifyAllConnections(scriptHash(0), status(0));
        backend.setStatus(scriptHash(1), status(1), true);
        assertNotified(client, scriptHash(1), status(1));
        assertNoNotification(client, 200);
    }

    @Test
    public void slowClientDoesNotDelayOtherSessions() throws Exception {
        List<String> slowScriptHashes = IntStream.range(0, 2000).mapToObj(ElectrumSessionIntegrationTest::scriptHash).toList();
        TestElectrumClient slow = connectClient(4096);
        JsonNode subscribed = slow.requestBatch("blockchain.scripthash.subscribe", slowScriptHashes);
        assertEquals(slowScriptHashes.size(), subscribed.size());
        TestElectrumClient healthy = connectClient();
        String healthyScriptHash = scriptHash(5000);
        subscribe(healthy, healthyScriptHash);

        //the slow client stops reading, and enough notifications for it follow to fill the socket buffers and block its writer
        slow.pauseReading();
        for(int round = 0; round < 10; round++) {
            for(String scriptHash : slowScriptHashes) {
                backend.setStatus(scriptHash, status(round), true);
            }
        }

        long start = System.nanoTime();
        backend.setStatus(healthyScriptHash, status(1), true);
        assertNotified(healthy, healthyScriptHash, status(1));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000);

        //coalesced statuses are bounded by the subscription count, so the slow client is not disconnected
        assertFalse(slow.awaitDisconnect(200, TimeUnit.MILLISECONDS));
        slow.resumeReading();
        await(() -> slow.getNotifications().stream().anyMatch(n -> TestElectrumClient.param(n, 1, "status").asText().equals(status(9))), "slow client to catch up");
    }
}
