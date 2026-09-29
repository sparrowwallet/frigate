package com.sparrowwallet.frigate.electrum;

import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcMethod;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcOptional;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcParam;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcService;
import com.google.common.net.HostAndPort;
import com.sparrowwallet.frigate.io.Protocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.sparrowwallet.frigate.electrum.FakeElectrumServer.*;
import static org.junit.jupiter.api.Assertions.*;

public class ElectrumTransportTest {
    private static final String SCRIPT_HASH = "a0".repeat(32);
    private static final String STATUS = "01".repeat(32);

    private final RecordingSubscriptionService subscriptionService = new RecordingSubscriptionService();
    private FakeElectrumServer server;
    private ElectrumTransport transport;
    private Thread reader;

    @BeforeEach
    public void setUp() throws IOException {
        server = new FakeElectrumServer();
    }

    @AfterEach
    public void tearDown() throws IOException {
        if(transport != null) {
            transport.close();
        }
        server.close();
    }

    private void connect(long requestTimeoutMillis) throws IOException {
        transport = new ElectrumTransport(server.getHostAndPort(), Protocol.TCP, subscriptionService, requestTimeoutMillis);
        transport.connect();
        reader = Thread.ofVirtual().name("TestReader").start(transport::readInputLoop);
    }

    @Test
    public void passReturnsMatchingResponse() throws Exception {
        connect(5000);

        String response = transport.pass(request(7, "server.ping"));

        assertEquals(response("7", "\"ok\""), response);
        assertEquals(request(7, "server.ping"), server.getReceived().poll(5, TimeUnit.SECONDS));
    }

    @Test
    public void notificationBeforeResponseHasLowerSequence() throws Exception {
        connect(5000);
        server.setResponder(line -> List.of(scriptHashNotification(SCRIPT_HASH, "\"" + STATUS + "\""), response(idOf(line), "null")));

        transport.pass(request(1, "blockchain.scripthash.subscribe"));
        long responseSequence = ElectrumTransport.getReadSequence();

        //the reader dispatches the notification before it reads the response, so it has already been handled
        Notification notification = subscriptionService.notifications.poll();
        assertNotNull(notification);
        assertEquals(SCRIPT_HASH, notification.scriptHash());
        assertEquals(STATUS, notification.status());
        assertTrue(notification.sequence() > 0);
        assertTrue(notification.sequence() < responseSequence);
    }

    @Test
    public void notificationAfterResponseHasHigherSequence() throws Exception {
        connect(5000);
        server.setResponder(line -> List.of(response(idOf(line), "null"), scriptHashNotification(SCRIPT_HASH, "null")));

        transport.pass(request(1, "blockchain.scripthash.subscribe"));
        long responseSequence = ElectrumTransport.getReadSequence();

        Notification notification = subscriptionService.notifications.poll(5, TimeUnit.SECONDS);
        assertNotNull(notification);
        assertNull(notification.status());
        assertTrue(notification.sequence() > responseSequence);
    }

    @Test
    public void veryLongTimeoutDoesNotOverflow() throws Exception {
        //one past Integer.MAX_VALUE milliseconds, which would wrap to a negative socket timeout if cast rather than clamped
        connect(Integer.MAX_VALUE + 1L);

        assertEquals(response("14", "\"ok\""), transport.pass(request(14, "server.ping")));
    }

    @Test
    public void staleResponseIsDiscarded() throws Exception {
        connect(5000);
        server.setResponder(line -> List.of(response("999", "\"stale\""), response(idOf(line), "\"fresh\"")));

        assertEquals(response("3", "\"fresh\""), transport.pass(request(3, "server.ping")));
    }

    @Test
    public void notificationsFlowWhenResponsesAreNotCollected() throws Exception {
        connect(5000);

        //responses nobody is waiting for must not stop the reader from dispatching notifications
        server.send(response("900", "null"));
        server.send(response("901", "null"));
        server.send(scriptHashNotification(SCRIPT_HASH, "null"));

        assertNotNull(subscriptionService.notifications.poll(5, TimeUnit.SECONDS));
    }

    @Test
    public void messageSplitAcrossWritesIsReassembled() throws Exception {
        connect(5000);
        server.setResponder(line -> List.of());

        Thread.ofVirtual().start(() -> {
            try {
                String reply = response(idOf(server.getReceived().take()), "\"split\"");
                server.sendPartial(reply.substring(0, 10));
                Thread.sleep(200);
                server.send(reply.substring(10));
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertEquals(response("4", "\"split\""), transport.pass(request(4, "server.ping")));
    }

    @Test
    public void requestTimeoutClosesConnection() throws Exception {
        connect(300);
        server.setResponder(line -> List.of());

        long start = System.nanoTime();
        BackendUnavailableException e = assertThrows(BackendUnavailableException.class, () -> transport.pass(request(5, "server.ping")));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals("request timed out", e.getReason());
        assertTrue(elapsedMillis >= 300 && elapsedMillis < 5000, "elapsed " + elapsedMillis);
        assertFalse(transport.isConnected());
        assertTrue(reader.join(java.time.Duration.ofSeconds(5)));

        //later requests fail immediately rather than waiting for another timeout
        start = System.nanoTime();
        e = assertThrows(BackendUnavailableException.class, () -> transport.pass(request(6, "server.ping")));
        assertEquals("not connected", e.getReason());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 300);
    }

    @Test
    public void connectionLostDuringRequest() throws Exception {
        connect(5000);
        server.setResponder(line -> {
            try {
                server.closeClient();
            } catch(IOException e) {
                throw new RuntimeException(e);
            }
            return List.of();
        });

        BackendUnavailableException e = assertThrows(BackendUnavailableException.class, () -> transport.pass(request(8, "server.ping")));

        assertEquals("connection lost", e.getReason());
        assertTrue(reader.join(java.time.Duration.ofSeconds(5)));
        assertNotNull(transport.getLastException());
        assertFalse(transport.isConnected());
    }

    @Test
    public void closeEndsReaderWithoutError() throws Exception {
        connect(5000);
        assertTrue(transport.isConnected());

        transport.close();

        assertTrue(reader.join(java.time.Duration.ofSeconds(5)));
        assertNull(transport.getLastException());
        BackendUnavailableException e = assertThrows(BackendUnavailableException.class, () -> transport.pass(request(9, "server.ping")));
        assertEquals("transport closed", e.getReason());
        assertThrows(BackendUnavailableException.class, () -> transport.connect());
    }

    @Test
    public void reconnectsAfterLostConnection() throws Exception {
        connect(5000);
        server.closeClient();
        assertTrue(reader.join(java.time.Duration.ofSeconds(5)));
        assertFalse(transport.isConnected());
        assertNotNull(transport.getLastException());

        transport.connect();
        reader = Thread.ofVirtual().name("TestReader2").start(transport::readInputLoop);

        assertTrue(transport.isConnected());
        assertNull(transport.getLastException());
        assertEquals(response("12", "\"ok\""), transport.pass(request(12, "server.ping")));
    }

    @Test
    public void lateExitOfOldReaderDoesNotAffectNewConnection() throws Exception {
        connect(5000);
        Thread oldReader = reader;

        //park the first connection's reader inside a notification handler
        CountDownLatch handling = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        subscriptionService.blockNextNotification(handling, release);
        server.send(scriptHashNotification(SCRIPT_HASH, "null"));
        assertTrue(handling.await(5, TimeUnit.SECONDS));

        //reconnect, replacing the first connection, while its reader is still running
        transport.connect();
        reader = Thread.ofVirtual().name("TestReader2").start(transport::readInputLoop);

        //the old reader now finds its connection closed and exits
        release.countDown();
        assertTrue(oldReader.join(java.time.Duration.ofSeconds(5)));

        assertTrue(transport.isConnected());
        assertNull(transport.getLastException());
        assertEquals(response("13", "\"ok\""), transport.pass(request(13, "server.ping")));
    }

    @Test
    public void bufferedNotificationFromReplacedConnectionIsDropped() throws Exception {
        connect(5000);
        Thread oldReader = reader;

        //both notifications arrive in one write, so the second is buffered in the old reader while it handles the first
        CountDownLatch handling = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        subscriptionService.blockNextNotification(handling, release);
        server.send(scriptHashNotification(SCRIPT_HASH, "\"" + STATUS + "\"") + "\n" + scriptHashNotification(SCRIPT_HASH, "null"));
        assertTrue(handling.await(5, TimeUnit.SECONDS));
        assertEquals(STATUS, subscriptionService.notifications.poll().status());

        transport.connect();
        reader = Thread.ofVirtual().name("TestReader2").start(transport::readInputLoop);
        release.countDown();
        assertTrue(oldReader.join(java.time.Duration.ofSeconds(5)));

        //the buffered notification from the replaced connection was not dispatched
        assertNull(subscriptionService.notifications.poll());
        server.awaitConnectionCount(2);
        server.send(scriptHashNotification(SCRIPT_HASH, "\"" + STATUS + "\""));
        Notification fromNewConnection = subscriptionService.notifications.poll(5, TimeUnit.SECONDS);
        assertNotNull(fromNewConnection);
        assertEquals(STATUS, fromNewConnection.status());
    }

    @Test
    public void requestWaitingOnReplacedConnectionFailsPromptly() throws Exception {
        connect(10_000);
        server.setResponder(line -> List.of());

        BlockingQueue<Exception> failure = new LinkedBlockingQueue<>();
        Thread requester = Thread.ofVirtual().start(() -> {
            try {
                transport.pass(request(15, "server.ping"));
            } catch(Exception e) {
                failure.add(e);
            }
        });
        assertNotNull(server.getReceived().poll(5, TimeUnit.SECONDS));

        //another thread replaces the connection the request is waiting on
        long start = System.nanoTime();
        transport.connect();

        Exception e = failure.poll(5, TimeUnit.SECONDS);
        assertInstanceOf(BackendUnavailableException.class, e);
        assertEquals("connection lost", ((BackendUnavailableException)e).getReason());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5000);
        assertTrue(requester.join(java.time.Duration.ofSeconds(5)));
        assertTrue(transport.isConnected());
    }

    @Test
    public void oversizedLineFromBackendClosesConnection() throws Exception {
        transport = new ElectrumTransport(server.getHostAndPort(), Protocol.TCP, subscriptionService, 5000, 100);
        transport.connect();
        reader = Thread.ofVirtual().name("TestReader").start(transport::readInputLoop);
        server.setResponder(line -> List.of(response(idOf(line), "\"" + "x".repeat(200) + "\"")));

        BackendUnavailableException e = assertThrows(BackendUnavailableException.class, () -> transport.pass(request(16, "server.ping")));

        assertEquals("connection lost", e.getReason());
        assertTrue(reader.join(java.time.Duration.ofSeconds(5)));
        assertInstanceOf(com.sparrowwallet.frigate.io.LineTooLongException.class, transport.getLastException());
        assertFalse(transport.isConnected());
    }

    @Test
    public void lineWithinLimitFromBackendIsAccepted() throws Exception {
        transport = new ElectrumTransport(server.getHostAndPort(), Protocol.TCP, subscriptionService, 5000, 100);
        transport.connect();
        reader = Thread.ofVirtual().name("TestReader").start(transport::readInputLoop);

        assertEquals(response("17", "\"ok\""), transport.pass(request(17, "server.ping")));
    }

    @Test
    public void stalledTlsHandshakeTimesOut() throws Exception {
        try(SilentServer silent = new SilentServer(0)) {
            transport = new ElectrumTransport(silent.getHostAndPort(), Protocol.SSL, subscriptionService, 300);

            long start = System.nanoTime();
            assertThrows(IOException.class, () -> transport.connect());
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(elapsedMillis >= 300 && elapsedMillis < 5000, "elapsed " + elapsedMillis);
            assertFalse(transport.isConnected());
        }
    }

    @Test
    public void writeBlockedByUnreadingBackendTimesOut() throws Exception {
        try(SilentServer silent = new SilentServer(4096)) {
            transport = new ElectrumTransport(silent.getHostAndPort(), Protocol.TCP, subscriptionService, 300);
            transport.connect();
            reader = Thread.ofVirtual().name("TestReader").start(transport::readInputLoop);

            //far larger than the socket buffers, so the write blocks until the watchdog closes the connection
            String request = "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"server.ping\",\"params\":[\"" + "x".repeat(32 * 1024 * 1024) + "\"]}";
            long start = System.nanoTime();
            BackendUnavailableException e = assertThrows(BackendUnavailableException.class, () -> transport.pass(request));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertEquals("request timed out", e.getReason());
            assertTrue(elapsedMillis >= 300 && elapsedMillis < 5000, "elapsed " + elapsedMillis);
            assertFalse(transport.isConnected());
            assertTrue(reader.join(java.time.Duration.ofSeconds(5)));
        }
    }

    @Test
    public void connectFailureThrows() throws Exception {
        int unusedPort;
        try(ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            unusedPort = socket.getLocalPort();
        }
        transport = new ElectrumTransport(HostAndPort.fromParts(InetAddress.getLoopbackAddress().getHostAddress(), unusedPort), Protocol.TCP, subscriptionService, 5000);

        assertThrows(IOException.class, () -> transport.connect());
        assertFalse(transport.isConnected());
        BackendUnavailableException e = assertThrows(BackendUnavailableException.class, () -> transport.pass(request(10, "server.ping")));
        assertEquals("not connected", e.getReason());
    }

    public record Notification(String scriptHash, String status, long sequence) {}

    /** Accepts connections and never reads or writes, like a backend that has hung. */
    private static class SilentServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final List<java.net.Socket> accepted = new java.util.concurrent.CopyOnWriteArrayList<>();

        SilentServer(int receiveBufferSize) throws IOException {
            serverSocket = new ServerSocket();
            if(receiveBufferSize > 0) {
                serverSocket.setReceiveBufferSize(receiveBufferSize);
            }
            serverSocket.bind(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            Thread.ofVirtual().start(() -> {
                try {
                    while(true) {
                        accepted.add(serverSocket.accept());
                    }
                } catch(IOException e) {
                    //closed
                }
            });
        }

        HostAndPort getHostAndPort() {
            return HostAndPort.fromParts(InetAddress.getLoopbackAddress().getHostAddress(), serverSocket.getLocalPort());
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
            for(java.net.Socket socket : accepted) {
                socket.close();
            }
        }
    }

    @JsonRpcService
    public static class RecordingSubscriptionService {
        private final BlockingQueue<Notification> notifications = new LinkedBlockingQueue<>();
        private volatile CountDownLatch handling;
        private volatile CountDownLatch release;

        /** Makes the next notification block its reader thread inside the handler until released. */
        void blockNextNotification(CountDownLatch handling, CountDownLatch release) {
            this.handling = handling;
            this.release = release;
        }

        @JsonRpcMethod("blockchain.scripthash.subscribe")
        public void scriptHashStatusUpdated(@JsonRpcParam("scripthash") final String scriptHash, @JsonRpcOptional @JsonRpcParam("status") final String status) {
            notifications.add(new Notification(scriptHash, status, ElectrumTransport.getReadSequence()));
            CountDownLatch toRelease = release;
            if(toRelease != null) {
                release = null;
                handling.countDown();
                try {
                    toRelease.await();
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
