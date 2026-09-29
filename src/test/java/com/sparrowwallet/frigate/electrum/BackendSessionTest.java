package com.sparrowwallet.frigate.electrum;

import com.google.common.net.HostAndPort;
import com.sparrowwallet.frigate.io.Protocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.sparrowwallet.frigate.electrum.FakeElectrumServer.*;
import static org.junit.jupiter.api.Assertions.*;

public class BackendSessionTest {
    private static final Pattern METHOD_PATTERN = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern FIRST_PARAM_PATTERN = Pattern.compile("\"params\"\\s*:\\s*\\[\\s*\"([^\"]*)\"");

    private static final String SCRIPT_HASH_A = "a0".repeat(32);
    private static final String SCRIPT_HASH_B = "b0".repeat(32);
    private static final String SCRIPT_HASH_C = "c0".repeat(32);
    private static final String STATUS_1 = "01".repeat(32);
    private static final String STATUS_2 = "02".repeat(32);
    private static final String STATUS_3 = "03".repeat(32);

    private volatile Runnable afterSubscribeResponse;
    private final ScriptHashSubscriptions subscriptions = new ScriptHashSubscriptions() {
        @Override
        public void recordSubscribeResponse(String scriptHash, String status, long sequence) {
            super.recordSubscribeResponse(scriptHash, status, sequence);
            Runnable hook = afterSubscribeResponse;
            if(hook != null) {
                hook.run();
            }
        }
    };
    private final List<String> delivered = Collections.synchronizedList(new ArrayList<>());
    private final List<String> received = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> backendStatuses = new ConcurrentHashMap<>();
    private volatile BackendSession.VersionRequest versionRequest;
    private volatile boolean answerPings = true;

    private FakeElectrumServer server;
    private AsyncNotifier notifier;
    private BackendSession session;

    @BeforeEach
    public void setUp() throws IOException {
        server = new FakeElectrumServer();
        server.setResponder(this::respond);
        notifier = new AsyncNotifier("test-notifier", 100, subscriptions, (scriptHash, status) -> delivered.add(scriptHash + ":" + status), () -> {});
        notifier.start();
    }

    @AfterEach
    public void tearDown() throws IOException {
        if(session != null) {
            session.close();
        }
        notifier.close();
        server.close();
    }

    /** A backend that answers server.version, scripthash subscribes (from backendStatuses) and pings. */
    private List<String> respond(String line) {
        String method = match(METHOD_PATTERN, line);
        received.add(method + (method.equals("blockchain.scripthash.subscribe") ? " " + match(FIRST_PARAM_PATTERN, line) : ""));
        String id = idOf(line);
        return switch(method) {
            case "server.version" -> List.of(response(id, "[\"FakeElectrum 1.0\",\"1.4\"]"));
            case "blockchain.scripthash.subscribe" -> {
                String status = backendStatuses.get(match(FIRST_PARAM_PATTERN, line));
                yield List.of(response(id, status == null ? "null" : "\"" + status + "\""));
            }
            case "server.ping" -> answerPings ? List.of(response(id, "null")) : List.of();
            default -> List.of(response(id, "null"));
        };
    }

    private static String match(Pattern pattern, String line) {
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? matcher.group(1) : "";
    }

    private BackendSession createSession(HostAndPort backend, long requestTimeoutMillis, BackendSession.Settings settings) {
        ElectrumTransport transport = new ElectrumTransport(backend, Protocol.TCP, new BackendSubscriptionService(subscriptions, notifier::notifyScriptHash), requestTimeoutMillis);
        session = new BackendSession("test-session", transport, subscriptions, notifier, () -> versionRequest, settings);
        return session;
    }

    private BackendSession startSession() throws InterruptedException {
        createSession(server.getHostAndPort(), 5000, new BackendSession.Settings(50, 200, 60_000));
        session.start();
        return session;
    }

    /** Sets up a subscription as the client's subscribe request would have left it. */
    private void subscribed(String scriptHash, String status) {
        subscriptions.subscribe(scriptHash);
        subscriptions.recordSubscribeResponse(scriptHash, status, 0);
    }

    private static void awaitCondition(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean()) {
            if(System.nanoTime() > deadline) {
                fail("Timed out waiting for " + description);
            }
            Thread.sleep(5);
        }
    }

    @Test
    public void startConnectsBeforeReturning() throws Exception {
        startSession();

        assertTrue(session.isConnected());
        assertEquals(1, session.getConnectCount());
        //nothing to restore on the first connection
        assertTrue(received.isEmpty());
    }

    @Test
    public void reconnectRestoresVersionAndDeliversChangedStatuses() throws Exception {
        startSession();
        versionRequest = new BackendSession.VersionRequest("TestWallet", "1.4");
        subscribed(SCRIPT_HASH_A, STATUS_1);
        subscribed(SCRIPT_HASH_B, STATUS_1);
        //A changes while the connection is down, B does not
        backendStatuses.put(SCRIPT_HASH_A, STATUS_2);
        backendStatuses.put(SCRIPT_HASH_B, STATUS_1);

        server.closeClient();

        awaitCondition(() -> session.getConnectCount() == 2 && received.size() == 3, "reconnect and restore");
        assertEquals("server.version", received.getFirst());
        assertEquals(List.of("blockchain.scripthash.subscribe " + SCRIPT_HASH_A, "blockchain.scripthash.subscribe " + SCRIPT_HASH_B),
                received.subList(1, 3).stream().sorted().toList());
        awaitCondition(() -> !delivered.isEmpty(), "catch-up delivery");
        assertEquals(List.of(SCRIPT_HASH_A + ":" + STATUS_2), delivered);
        assertEquals(STATUS_2, subscriptions.getStatus(SCRIPT_HASH_A));
        assertEquals(STATUS_1, subscriptions.getStatus(SCRIPT_HASH_B));
        assertTrue(session.isConnected());
    }

    @Test
    public void reconnectWithoutNegotiatedVersionSkipsServerVersion() throws Exception {
        startSession();
        subscribed(SCRIPT_HASH_A, null);

        server.closeClient();

        awaitCondition(() -> received.size() == 1, "resubscribe");
        assertEquals(List.of("blockchain.scripthash.subscribe " + SCRIPT_HASH_A), received);
        Thread.sleep(50);
        assertTrue(delivered.isEmpty());
    }

    @Test
    public void pendingSubscriptionIsNotResubscribed() throws Exception {
        startSession();
        subscribed(SCRIPT_HASH_A, STATUS_1);
        //a subscribe whose response had not arrived when the connection was lost
        subscriptions.subscribe(SCRIPT_HASH_C);

        server.closeClient();

        awaitCondition(() -> received.size() == 1, "resubscribe");
        Thread.sleep(100);
        assertEquals(List.of("blockchain.scripthash.subscribe " + SCRIPT_HASH_A), received);
    }

    @Test
    public void newerNotificationAfterResubscribeResponseWins() throws Exception {
        startSession();
        subscribed(SCRIPT_HASH_A, STATUS_1);
        //on resubscribe, the backend responds with STATUS_2 and immediately notifies a newer STATUS_3
        server.setResponder(line -> {
            List<String> replies = new ArrayList<>(respond(line));
            if(line.contains("blockchain.scripthash.subscribe")) {
                replies.add(scriptHashNotification(SCRIPT_HASH_A, "\"" + STATUS_3 + "\""));
            }
            return replies;
        });
        backendStatuses.put(SCRIPT_HASH_A, STATUS_2);
        //after recording the resubscribe response, give the newer notification every chance to reach the client first
        afterSubscribeResponse = () -> {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
            while(delivered.isEmpty() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
        };

        server.closeClient();

        awaitCondition(() -> !delivered.isEmpty(), "delivery");
        Thread.sleep(100);
        //the resubscribed scripthash is held, so the newer notification waits and supersedes the older catch-up status
        assertEquals(STATUS_3, subscriptions.getStatus(SCRIPT_HASH_A));
        assertEquals(List.of(SCRIPT_HASH_A + ":" + STATUS_3), delivered);
    }

    @Test
    public void recoversWhenBackendBecomesAvailable() throws Exception {
        int port;
        try(ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        createSession(HostAndPort.fromParts(InetAddress.getLoopbackAddress().getHostAddress(), port), 5000, new BackendSession.Settings(50, 200, 60_000));

        session.start();

        //the first attempt failed and start() returned; proxied requests fail fast meanwhile
        assertFalse(session.isConnected());
        assertEquals(0, session.getConnectCount());

        try(FakeElectrumServer lateServer = new FakeElectrumServer(port)) {
            awaitCondition(session::isConnected, "connection to late backend");
            assertEquals(1, session.getConnectCount());
        }
    }

    @Test
    public void idleConnectionIsPinged() throws Exception {
        createSession(server.getHostAndPort(), 5000, new BackendSession.Settings(50, 200, 150));
        session.start();

        awaitCondition(() -> received.contains("server.ping"), "keepalive ping");
        assertTrue(session.isConnected());
        assertEquals(1, session.getConnectCount());
    }

    @Test
    public void unansweredPingReconnects() throws Exception {
        answerPings = false;
        createSession(server.getHostAndPort(), 200, new BackendSession.Settings(50, 200, 150));
        session.start();

        awaitCondition(() -> session.getConnectCount() >= 2, "reconnect after ping timeout");
        assertTrue(received.contains("server.ping"));
    }

    @Test
    public void closeStopsSupervisorWaitingToReconnect() throws Exception {
        int port;
        try(ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        //a long backoff, so the supervisor is sleeping between attempts when closed
        createSession(HostAndPort.fromParts(InetAddress.getLoopbackAddress().getHostAddress(), port), 5000, new BackendSession.Settings(60_000, 60_000, 60_000));
        session.start();

        session.close();

        assertTrue(session.awaitTermination(1000));
    }

    @Test
    public void closeEndsConnectedSession() throws Exception {
        startSession();

        session.close();

        assertTrue(session.awaitTermination(1000));
        assertFalse(session.isConnected());
    }
}
