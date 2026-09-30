package com.sparrowwallet.frigate.electrum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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
    private static final int MAX_REQUEST_BYTES = 4000;
    private static final int MAX_BATCH_SIZE = 25;

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
        config.getLimits().setMaxRequestBytes(MAX_REQUEST_BYTES);
        config.getLimits().setMaxBatchSize(MAX_BATCH_SIZE);
        //test clients connect over loopback, which is excluded from limits by default
        config.getLimits().setExcludedSubnets(List.of());
        Config.setInstance(config);
        startServer();
    }

    /** Starts a server, reading the current config; a test changing limits read at startup restarts it. */
    private void startServer() {
        startServer(runnable -> {});
    }

    /** Starts a server, configuring it before it runs. */
    private void startServer(java.util.function.Consumer<ElectrumServerRunnable> configure) {
        if(server != null) {
            server.stop();
        }
        server = new ElectrumServerRunnable(null, null, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, null);
        configure.accept(server);
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
        assertEquals(scriptHash, TestElectrumClient.param(notification, 0).asText());
        //the status is always present, and a null status is sent explicitly as null
        assertEquals(2, notification.path("params").size(), notification.toString());
        JsonNode notifiedStatus = TestElectrumClient.param(notification, 1);
        assertEquals(status, notifiedStatus.isNull() ? null : notifiedStatus.asText());
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
    public void oversizedRequestIsRejectedAndDisconnected() throws Exception {
        //a raw socket that nothing reads until the session has ended, like a client still busy writing a large request
        try(Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.getTcpLocalPort())) {
            OutputStream out = socket.getOutputStream();
            //far larger than the server reads before rejecting it, so most of it is unread when the session closes
            byte[] request = ("{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"server.ping\",\"params\":[\"" + "x".repeat(50 * MAX_REQUEST_BYTES) + "\"]}\n")
                    .getBytes(StandardCharsets.UTF_8);
            Thread writer = Thread.ofVirtual().start(() -> {
                try {
                    out.write(request);
                    out.flush();
                } catch(IOException e) {
                    //the server may reset the connection before the whole request is written
                }
            });
            writer.join(java.time.Duration.ofSeconds(10));
            //past the server's drain deadline, so the session has closed before the client reads
            Thread.sleep(3000);

            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            JsonNode error = new ObjectMapper().readTree(in.readLine());
            assertEquals(-32600, errorCode(error));
            assertEquals("request too large", error.path("error").path("message").asText());
            assertTrue(error.path("id").isNull());
            assertNull(in.readLine());
        }
    }

    @Test
    public void requestAtLimitIsAccepted() throws Exception {
        TestElectrumClient client = connectClient();
        String prefix = "{\"jsonrpc\":\"2.0\",\"id\":98,\"method\":\"blockchain.scripthash.get_history\",\"params\":[\"";
        String suffix = "\"]}";
        client.sendRaw(prefix + "x".repeat(MAX_REQUEST_BYTES - prefix.length() - suffix.length()) + suffix);

        JsonNode response = client.awaitResponse(98);
        assertFalse(response.has("error") && !response.get("error").isNull(), response.toString());
    }

    @Test
    public void batchAtLimitIsProcessed() throws Exception {
        TestElectrumClient client = connectClient();
        List<String> scriptHashes = IntStream.range(0, MAX_BATCH_SIZE).mapToObj(ElectrumSessionIntegrationTest::scriptHash).toList();

        JsonNode responses = client.requestBatch("blockchain.scripthash.get_history", scriptHashes);

        assertEquals(MAX_BATCH_SIZE, responses.size());
        for(JsonNode response : responses) {
            assertTrue(response.path("result").isArray(), response.toString());
        }
    }

    @Test
    public void defaultBatchLimitAcceptsSparrowsMerkleProofBatches() throws Exception {
        Config.get().getLimits().setMaxBatchSize(null);
        Config.get().getLimits().setMaxRequestBytes(null);
        TestElectrumClient client = connectClient();
        List<String> scriptHashes = IntStream.range(0, 250).mapToObj(ElectrumSessionIntegrationTest::scriptHash).toList();

        JsonNode responses = client.requestBatch("blockchain.scripthash.get_history", scriptHashes);

        assertEquals(250, responses.size());
        for(JsonNode response : responses) {
            assertTrue(response.path("result").isArray(), response.toString());
        }
    }

    @Test
    public void batchOverLimitIsRejectedAndSessionContinues() throws Exception {
        TestElectrumClient client = connectClient();
        List<String> scriptHashes = IntStream.range(0, MAX_BATCH_SIZE + 1).mapToObj(ElectrumSessionIntegrationTest::scriptHash).toList();

        client.sendBatch("blockchain.scripthash.subscribe", scriptHashes);

        JsonNode error = client.pollResponse(5, TimeUnit.SECONDS);
        assertNotNull(error);
        assertEquals(-32600, errorCode(error));
        assertEquals("batch too large", error.path("error").path("message").asText());
        assertTrue(error.path("id").isNull());
        //none of the batch was processed
        assertTrue(backend.getSubscribedConnections(scriptHash(0)).isEmpty());
        assertTrue(client.request("blockchain.scripthash.get_history", scriptHash(0)).path("result").isArray());
    }

    @Test
    public void excludedClientsSkipTheBatchCap() throws Exception {
        Config.get().getLimits().setExcludedSubnets(null);
        startServer();
        TestElectrumClient client = connectClient();
        List<String> scriptHashes = IntStream.range(0, MAX_BATCH_SIZE + 1).mapToObj(ElectrumSessionIntegrationTest::scriptHash).toList();

        JsonNode responses = client.requestBatch("blockchain.scripthash.get_history", scriptHashes);

        assertEquals(MAX_BATCH_SIZE + 1, responses.size());
    }

    @Test
    public void requestWithControlCharactersGetsParseError() throws Exception {
        TestElectrumClient client = connectClient();

        client.sendRaw("{\"jsonrpc\":\"2.0\",\"id\":97,\"method\":\"server.ping\",\"params\":[\"\u0001\"]}");

        JsonNode error = client.pollResponse(5, TimeUnit.SECONDS);
        assertNotNull(error);
        assertEquals(-32700, errorCode(error));
        assertTrue(error.path("id").isNull());
        //the session continues
        assertTrue(client.request("blockchain.scripthash.get_history", scriptHash(0)).path("result").isArray());
    }

    @Test
    public void connectionsBeyondGlobalCapAreRefused() throws Exception {
        Config.get().getLimits().setMaxConnections(2);
        startServer();
        TestElectrumClient first = connectClient();
        connectClient();

        //refused without a response: the connection is simply closed
        TestElectrumClient refused = new TestElectrumClient(server.getTcpLocalPort());
        clients.add(refused);
        refused.send("server.version", "TestWallet", "1.4");
        assertTrue(refused.awaitDisconnect(5, TimeUnit.SECONDS));
        assertNull(refused.pollResponse(0, TimeUnit.MILLISECONDS));
        assertEquals(2, server.getSessions().size());
        assertEquals(2, backend.getOpenConnections().size());

        //a slot frees when a session ends
        first.close();
        await(() -> server.getSessions().size() == 1 && server.getConnectionGate().getConnectionCount() == 1, "session to end");
        TestElectrumClient admitted = connectClient();
        assertTrue(admitted.request("blockchain.scripthash.get_history", scriptHash(0)).path("result").isArray());
        assertEquals(2, server.getConnectionGate().getConnectionCount());
    }

    private static void assertSubscriptionLimitError(JsonNode response) {
        assertEquals(-32005, errorCode(response), response.toString());
        assertEquals("subscription limit exceeded", response.path("error").path("message").asText());
    }

    @Test
    public void connectionsBeyondPerIpCapAreRefused() throws Exception {
        Config.get().getLimits().setMaxConnectionsPerIp(2);
        startServer();
        connectClient();
        connectClient();

        TestElectrumClient refused = new TestElectrumClient(server.getTcpLocalPort());
        clients.add(refused);
        refused.send("server.version", "TestWallet", "1.4");
        assertTrue(refused.awaitDisconnect(5, TimeUnit.SECONDS));
        assertEquals(2, server.getSessions().size());
    }

    @Test
    public void subscriptionsBeyondPerIpCapAreRefused() throws Exception {
        Config.get().getLimits().setMaxSubscriptionsPerIp(3);
        startServer();
        TestElectrumClient first = connectClient();
        subscribe(first, scriptHash(0));
        subscribe(first, scriptHash(1));
        TestElectrumClient second = connectClient();
        subscribe(second, scriptHash(2));

        //the cap is shared by the sessions from one address
        assertSubscriptionLimitError(second.request("blockchain.scripthash.subscribe", scriptHash(3)));
        //the refused subscription never reached the backend
        assertTrue(backend.getSubscribedConnections(scriptHash(3)).isEmpty());
        //a subscription the client already has is not counted again
        subscribe(second, scriptHash(2));

        //an unsubscribe frees a slot
        first.request("blockchain.scripthash.unsubscribe", scriptHash(0));
        subscribe(second, scriptHash(3));
        assertEquals(3, server.getConnectionGate().getSubscriptionCount());
    }

    @Test
    public void subscriptionsBeyondGlobalCapAreRefusedUntilReleased() throws Exception {
        Config.get().getLimits().setMaxSubscriptions(3L);
        startServer();
        TestElectrumClient first = connectClient();
        subscribe(first, scriptHash(0));
        subscribe(first, scriptHash(1));
        TestElectrumClient second = connectClient();
        subscribe(second, scriptHash(2));

        assertSubscriptionLimitError(second.request("blockchain.scripthash.subscribe", scriptHash(3)));

        //a disconnecting client's subscriptions are released
        first.close();
        await(() -> server.getConnectionGate().getSubscriptionCount() == 1, "subscriptions of disconnected client to be released");
        subscribe(second, scriptHash(3));
        assertEquals(2, server.getConnectionGate().getSubscriptionCount());
    }

    @Test
    public void failedSubscribeDoesNotLeakReservation() throws Exception {
        TestElectrumClient client = connectClient();
        subscribe(client, scriptHash(0));
        assertEquals(1, server.getConnectionGate().getSubscriptionCount());

        backend.setAccepting(false);
        backend.dropAllConnections();
        await(() -> backend.getOpenConnections().isEmpty(), "backend connections to drop");

        //a new subscription fails with the backend down, and its reservation is given back
        assertEquals(-32000, errorCode(client.request("blockchain.scripthash.subscribe", scriptHash(1))));
        assertEquals(1, server.getConnectionGate().getSubscriptionCount());
        backend.setAccepting(true);
    }

    @Test
    public void ordinaryRequestsAreNotPaced() throws Exception {
        Config.get().getLimits().setSilentPaymentsSubscribeBurst(1);
        Config.get().getLimits().setSilentPaymentsSubscribeIntervalSeconds(10);
        TestElectrumClient client = connectClient();
        long pacedBefore = server.getStats().silentPaymentsSubscribesPaced();

        long start = System.nanoTime();
        List<Integer> ids = new ArrayList<>();
        for(int i = 0; i < 24; i++) {
            ids.add(client.send("blockchain.scripthash.get_history", scriptHash(i)));
        }
        for(int id : ids) {
            assertTrue(client.awaitResponse(id).path("result").isArray());
        }

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMillis < 1500, "elapsed " + elapsedMillis + "ms");
        assertEquals(0, server.getStats().silentPaymentsSubscribesPaced() - pacedBefore);
    }

    @Test
    public void statsReflectSessionsSubscriptionsAndEvents() throws Exception {
        ServerStats baseline = server.getStats();
        TestElectrumClient first = connectClient();
        for(int i = 0; i < 3; i++) {
            subscribe(first, scriptHash(i));
        }
        TestElectrumClient second = connectClient();
        subscribe(second, scriptHash(10));
        backend.setStatus(scriptHash(0), status(0), true);
        backend.setStatus(scriptHash(10), status(10), true);
        assertNotified(first, scriptHash(0), status(0));
        assertNotified(second, scriptHash(10), status(10));

        await(() -> server.getStats().backendConnected() == 2, "backend connections");
        ServerStats stats = server.getStats();
        assertEquals(2, stats.sessions());
        assertEquals(1, stats.distinctIps());
        assertEquals(4, stats.scriptHashSubscriptions());
        assertTrue(stats.backendConfigured());
        assertEquals(2, stats.backendSessions());
        assertNull(stats.tipHeight());
        //other tests in the same process may have delivered notifications before the baseline, so compare changes
        assertEquals(2, stats.notificationsDelivered() - baseline.notificationsDelivered());

        //a closed session's delivered notifications still count, and a backend restart counts a reconnect per session
        first.close();
        await(() -> server.getStats().sessions() == 1, "session to close");
        backend.dropAllConnections();
        await(() -> server.getStats().backendReconnects() - baseline.backendReconnects() >= 1 && server.getStats().backendConnected() == 1, "reconnect");
        ServerStats after = server.getStats();
        assertEquals(1, after.sessions());
        assertEquals(1, after.scriptHashSubscriptions());
        assertEquals(2, after.notificationsDelivered() - baseline.notificationsDelivered());
    }

    @Test
    public void statsLinesAreLoggedWhenEnabled() throws Exception {
        List<String> lines = statsLinesLogged(true, true);
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("Server health: backend connected, no reconnects or request timeouts in ")), lines.toString());
        //fewer than ten of everything, so the usage line is suppressed
        assertTrue(lines.stream().noneMatch(line -> line.startsWith("Aggregate server stats")), lines.toString());

        assertEquals(List.of(), statsLinesLogged(false, false));
    }

    /**
     * Restarts the server with the health and usage lines enabled or not, at short intervals, and with a connected client, returning
     * the stats lines logged within 2.5s.
     */
    private List<String> statsLinesLogged(boolean health, boolean usage) throws Exception {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(ElectrumServerRunnable.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Config.get().getServer().setHealthStatsEnabled(health);
            Config.get().getScan().setMetricsEnabled(usage);
            startServer(runnable -> runnable.setStatsIntervals(java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1)));
            TestElectrumClient client = connectClient();
            Thread.sleep(2500);
            client.close();
            return appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.startsWith("Server health") || message.startsWith("Aggregate server stats")).toList();
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    public void donationAddressIsServedFromConfigNotTheBackend() throws Exception {
        TestElectrumClient client = connectClient();
        assertEquals("", client.request("server.donation_address").path("result").asText());

        Config.get().getServer().setDonationAddress("bc1qexampledonation");
        assertEquals("bc1qexampledonation", client.request("server.donation_address").path("result").asText());
    }

    @Test
    public void bannerIsServedFromFile() throws Exception {
        TestElectrumClient client = connectClient();
        assertTrue(client.request("server.banner").path("result").asText().startsWith("Frigate"));

        java.nio.file.Path banner = java.nio.file.Files.createTempFile("banner", ".txt");
        try {
            java.nio.file.Files.writeString(banner, "Operated by example.com");
            Config.get().getServer().setBannerFile(banner.toString());
            assertEquals("Operated by example.com", client.request("server.banner").path("result").asText());
        } finally {
            java.nio.file.Files.deleteIfExists(banner);
        }
    }

    @Test
    public void idleClientIsDisconnected() throws Exception {
        Config.get().getLimits().setSessionTimeoutSeconds(1);
        TestElectrumClient client = connectClient();
        subscribe(client, scriptHash(0));
        FakeElectrumBackend.Connection clientBackend = backend.getSubscribedConnections(scriptHash(0)).getFirst();

        long start = System.nanoTime();
        assertTrue(client.awaitDisconnect(10, TimeUnit.SECONDS));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMillis >= 900, "disconnected after " + elapsedMillis + "ms");
        await(() -> !clientBackend.isOpen(), "backend connection to close with the session");
    }

    @Test
    public void pingingClientStaysConnected() throws Exception {
        Config.get().getLimits().setSessionTimeoutSeconds(1);
        TestElectrumClient client = connectClient();

        for(int i = 0; i < 8; i++) {
            Thread.sleep(300);
            JsonNode pong = client.request("server.ping");
            assertTrue(pong.has("result") && pong.get("result").isNull(), pong.toString());
        }

        assertFalse(client.awaitDisconnect(0, TimeUnit.MILLISECONDS));
    }

    @Test
    public void requestArrivingSlowlyKeepsSessionAlive() throws Exception {
        Config.get().getLimits().setSessionTimeoutSeconds(1);
        TestElectrumClient client = connectClient();

        //a request delivered in pieces over longer than the timeout, each piece arriving within it
        String request = "{\"jsonrpc\":\"2.0\",\"id\":96,\"method\":\"blockchain.scripthash.get_history\",\"params\":[\"" + scriptHash(0) + "\"]}";
        int pieceLength = request.length() / 8 + 1;
        for(int i = 0; i < request.length(); i += pieceLength) {
            client.sendPartial(request.substring(i, Math.min(i + pieceLength, request.length())));
            Thread.sleep(300);
        }
        client.sendPartial("\n");

        assertTrue(client.awaitResponse(96).path("result").isArray());
        assertFalse(client.awaitDisconnect(0, TimeUnit.MILLISECONDS));
    }

    @Test
    public void slowClientDoesNotDelayOtherSessions() throws Exception {
        List<String> slowScriptHashes = IntStream.range(0, 2000).mapToObj(ElectrumSessionIntegrationTest::scriptHash).toList();
        TestElectrumClient slow = connectClient(4096);
        //in batches that fit within the request size limit
        for(int i = 0; i < slowScriptHashes.size(); i += 25) {
            List<String> batch = slowScriptHashes.subList(i, Math.min(i + 25, slowScriptHashes.size()));
            assertEquals(batch.size(), slow.requestBatch("blockchain.scripthash.subscribe", batch).size());
        }
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
        await(() -> slow.getNotifications().stream().anyMatch(n -> TestElectrumClient.param(n, 1).asText().equals(status(9))), "slow client to catch up");
    }
}
