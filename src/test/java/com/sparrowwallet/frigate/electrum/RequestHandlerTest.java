package com.sparrowwallet.frigate.electrum;

import com.fasterxml.jackson.databind.JsonNode;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.silentpayments.SilentPaymentScanAddress;
import com.sparrowwallet.frigate.io.Config;
import com.sparrowwallet.frigate.io.SslUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static com.sparrowwallet.frigate.electrum.FakeElectrumBackend.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests a single session's RequestHandler wired directly to a test client, over plain TCP or TLS, for behaviour that needs direct
 * access to the session: silent payments delivery and slow client disconnection.
 */
public class RequestHandlerTest {
    private static final String SCAN_PRIVATE_KEY = "df80725d72aa91f09bf1961e78743b0b0a1b48c3218e369eb7ed08dfe857907b";
    private static final String SPEND_PUBLIC_KEY = "03ef6df342883059d5261e12b865b51a3d1761e6b51736fbd9936ce385b5d8005c";
    private static final String KEYSTORE_PASSWORD = "changeit";

    @TempDir
    static Path tempDir;
    private static SSLContext serverSslContext;

    private FakeElectrumBackend backend;
    private ServerSocket listener;
    private TestElectrumClient client;
    private RequestHandler handler;
    private Thread handlerThread;

    @BeforeAll
    public static void createCertificate() throws Exception {
        File keystore = tempDir.resolve("test.p12").toFile();
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "test", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=localhost",
                "-validity", "1", "-storetype", "PKCS12", "-keystore", keystore.getAbsolutePath(), "-storepass", KEYSTORE_PASSWORD, "-keypass", KEYSTORE_PASSWORD)
                .redirectErrorStream(true).start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0, "keytool failed: " + new String(process.getInputStream().readAllBytes()));

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try(InputStream in = new FileInputStream(keystore)) {
            keyStore.load(in, KEYSTORE_PASSWORD.toCharArray());
        }
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, KEYSTORE_PASSWORD.toCharArray());
        serverSslContext = SSLContext.getInstance("TLS");
        serverSslContext.init(keyManagerFactory.getKeyManagers(), null, null);
    }

    @BeforeEach
    public void setUp() throws IOException {
        backend = new FakeElectrumBackend();
        Config config = new Config();
        config.getServer().setBackendElectrumServer(backend.getUrl());
        config.getServer().setBackendRequestTimeoutSeconds(5);
        Config.setInstance(config);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if(client != null) {
            client.close();
        }
        if(handlerThread != null) {
            handlerThread.join(Duration.ofSeconds(5));
        }
        if(listener != null) {
            listener.close();
        }
        backend.close();
        Config.setInstance(null);
    }

    /**
     * Connects a test client to a new session, over TLS if ssl is true.
     * @param receiveBufferSize the client's socket receive buffer size, small to make a paused client stall the session's writes
     */
    private void connect(boolean ssl, int receiveBufferSize) throws Exception {
        listener = ssl ? serverSslContext.getServerSocketFactory().createServerSocket(0, 1, InetAddress.getLoopbackAddress())
                : new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Socket clientSocket = ssl ? SslUtil.getTrustAllSocketFactory().createSocket() : new Socket();
        client = new TestElectrumClient(clientSocket, listener.getLocalPort(), receiveBufferSize);
        Socket serverSide = listener.accept();

        handler = new RequestHandler(serverSide, null, null);
        handlerThread = Thread.ofVirtual().name("TestRequestHandler").start(handler);
        client.request("server.version", "TestWallet", "1.4");
    }

    private static SilentPaymentScanAddress scanAddress() {
        return SilentPaymentScanAddress.from(ECKey.fromPrivate(Utils.hexToBytes(SCAN_PRIVATE_KEY)), ECKey.fromPublicOnly(Utils.hexToBytes(SPEND_PUBLIC_KEY)));
    }

    private static SilentPaymentsNotification silentPaymentsNotification(SilentPaymentScanAddress address, int entries) {
        List<SilentPaymentsTxEntry> history = IntStream.range(0, entries)
                .mapToObj(i -> new SilentPaymentsTxEntry(100 + i, String.format("%064x", i), "02" + String.format("%064x", i))).toList();
        return new SilentPaymentsNotification(new SilentPaymentsSubscription(address.toString(), new Integer[] {0}, 0), 1.0, new ArrayList<>(history));
    }

    /**
     * Stops the client reading and blocks the session's notification writer. The client's reader may already be inside a read,
     * which consumes one more message before the pause takes effect, so two large notifications are sent: the first is consumed
     * by that read, and the second fills the socket buffers and blocks the writer.
     */
    private void stallClient(SilentPaymentScanAddress address) throws InterruptedException {
        client.pauseReading();
        handler.notifySilentPayments(silentPaymentsNotification(address, 50_000));
        handler.notifySilentPayments(silentPaymentsNotification(address, 50_000));
        Thread.sleep(500);
    }

    private SilentPaymentScanAddress subscribeSilentPayments(boolean active) {
        SilentPaymentScanAddress address = scanAddress();
        handler.subscribeSilentPaymentsAddress(address, Set.of(0), 0);
        handler.getSilentPaymentsAddressSubscription(address.toString()).setActive(active);
        return address;
    }

    @Test
    public void silentPaymentsNotificationRequiresActiveSubscription() throws Exception {
        connect(false, 0);
        SilentPaymentScanAddress address = subscribeSilentPayments(false);

        //not yet active: the subscribe response has not been sent
        handler.notifySilentPayments(silentPaymentsNotification(address, 1));
        assertNull(client.pollNotification(200, TimeUnit.MILLISECONDS));

        handler.getSilentPaymentsAddressSubscription(address.toString()).setActive(true);
        handler.notifySilentPayments(silentPaymentsNotification(address, 1));
        JsonNode notification = client.pollNotification(5, TimeUnit.SECONDS);
        assertNotNull(notification);
        assertEquals("blockchain.silentpayments.subscribe", notification.path("method").asText());
        assertEquals(1, TestElectrumClient.param(notification, 2, "history").size());

        //no longer subscribed
        handler.unsubscribeSilentPaymentsAddress(address);
        handler.notifySilentPayments(silentPaymentsNotification(address, 1));
        assertNull(client.pollNotification(200, TimeUnit.MILLISECONDS));
    }

    @Test
    public void silentPaymentsNotificationForOtherAddressIsNotDelivered() throws Exception {
        connect(false, 0);
        subscribeSilentPayments(true);
        SilentPaymentScanAddress other = SilentPaymentScanAddress.from(ECKey.fromPrivate(Utils.hexToBytes("11".repeat(32))), ECKey.fromPublicOnly(Utils.hexToBytes(SPEND_PUBLIC_KEY)));

        handler.notifySilentPayments(silentPaymentsNotification(other, 1));

        assertNull(client.pollNotification(200, TimeUnit.MILLISECONDS));
    }

    @Test
    public void silentPaymentsNotificationAfterDisconnectIsDropped() throws Exception {
        connect(false, 0);
        SilentPaymentScanAddress address = subscribeSilentPayments(true);

        client.close();
        assertTrue(handlerThread.join(Duration.ofSeconds(5)));
        assertFalse(handler.isConnected());

        assertDoesNotThrow(() -> handler.notifySilentPayments(silentPaymentsNotification(address, 1)));
    }

    @Test
    public void statusQueuedBeforeUnsubscribeIsNotDelivered() throws Exception {
        connect(false, 4096);
        String scriptHash = "a0".repeat(32);
        client.request("blockchain.scripthash.subscribe", scriptHash);
        SilentPaymentScanAddress address = subscribeSilentPayments(true);

        //stall the session's writer, then change the status so it queues behind the blocked write
        stallClient(address);
        backend.setStatus(scriptHash, "01".repeat(32), true);
        Thread.sleep(200);

        //the status is queued behind the blocked write when the client unsubscribes
        int unsubscribeId = client.send("blockchain.scripthash.unsubscribe", scriptHash);
        await(() -> backend.getSubscribedConnections(scriptHash).isEmpty(), "unsubscribe to reach the backend");
        client.resumeReading();

        assertTrue(client.awaitResponse(unsubscribeId).path("result").asBoolean());
        for(int i = 0; i < 2; i++) {
            JsonNode notification = client.pollNotification(10, TimeUnit.SECONDS);
            assertEquals("blockchain.silentpayments.subscribe", notification.path("method").asText());
        }
        assertNull(client.pollNotification(300, TimeUnit.MILLISECONDS));
    }

    @Test
    public void slowClientIsDisconnected() throws Exception {
        assertSlowClientDisconnected(false);
    }

    @Test
    public void slowClientIsDisconnectedOverTls() throws Exception {
        //an SSL socket's graceful close waits without limit for the blocked writer's lock, so this hangs without an abortive close
        assertSlowClientDisconnected(true);
    }

    private void assertSlowClientDisconnected(boolean ssl) throws Exception {
        connect(ssl, 4096);
        SilentPaymentScanAddress address = subscribeSilentPayments(true);

        //stall the writer, then overflow the notification queue behind it
        stallClient(address);
        for(int i = 0; i <= AsyncNotifier.DEFAULT_QUEUE_SIZE; i++) {
            handler.notifySilentPayments(silentPaymentsNotification(address, 1));
        }

        //the session tears itself down: the request thread ends and the client sees the connection closed
        assertTrue(handlerThread.join(Duration.ofSeconds(10)), "session did not end after overflow");
        assertFalse(handler.isConnected());
        await(() -> backend.getOpenConnections().isEmpty(), "backend connection to close with the session");
        client.resumeReading();
        assertTrue(client.awaitDisconnect(10, TimeUnit.SECONDS));
    }
}
