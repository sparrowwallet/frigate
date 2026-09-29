package com.sparrowwallet.frigate.io;

import com.sparrowwallet.frigate.ConfigurationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class CertificateReloaderTest {
    @TempDir
    static Path generatedDir;
    private static TestCertificates.Pem certA;
    private static TestCertificates.Pem certB;

    @TempDir
    Path dir;
    private File certFile;
    private File keyFile;
    private CertificateReloader reloader;
    private SSLServerSocket serverSocket;

    @BeforeAll
    public static void generateCertificates() throws Exception {
        certA = TestCertificates.generate(generatedDir, "a");
        certB = TestCertificates.generate(generatedDir, "b");
    }

    @BeforeEach
    public void setUp() throws IOException {
        certFile = dir.resolve("cert.pem").toFile();
        keyFile = dir.resolve("key.pem").toFile();
        write(certA.certificate(), certA.key());
    }

    @AfterEach
    public void tearDown() throws IOException {
        if(reloader != null) {
            reloader.close();
        }
        if(serverSocket != null) {
            serverSocket.close();
        }
    }

    private void write(String certificate, String key) throws IOException {
        Files.writeString(certFile.toPath(), certificate);
        Files.writeString(keyFile.toPath(), key);
    }

    private static String commonName(X509Certificate certificate) {
        return certificate.getSubjectX500Principal().getName();
    }

    /** Starts a TLS echo server using the reloader's key manager, as the Electrum server's SSL listener does. */
    private void startServer() throws IOException {
        serverSocket = (SSLServerSocket)SslUtil.getServerSSLContext(reloader.getKeyManager()).getServerSocketFactory().createServerSocket(0, 10, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            try {
                while(true) {
                    Socket socket = serverSocket.accept();
                    Thread.ofVirtual().start(() -> {
                        try(socket; BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
                            String line;
                            while((line = in.readLine()) != null) {
                                out.println(line);
                            }
                        } catch(IOException e) {
                            //closed
                        }
                    });
                }
            } catch(IOException e) {
                //closed
            }
        });
    }

    private SSLSocket connect() throws IOException {
        SSLSocket socket = (SSLSocket)SslUtil.getTrustAllSocketFactory().createSocket(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort());
        socket.startHandshake();
        return socket;
    }

    private static String servedName(SSLSocket socket) throws Exception {
        return commonName((X509Certificate)socket.getSession().getPeerCertificates()[0]);
    }

    private static String echo(Socket socket, String line) throws IOException {
        PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        out.println(line);
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
    }

    @Test
    public void newHandshakesUseReloadedCertificateWhileExistingSessionsContinue() throws Exception {
        reloader = new CertificateReloader(certFile, keyFile, 300);
        startServer();
        try(SSLSocket before = connect()) {
            assertEquals("CN=a", servedName(before));

            write(certB.certificate(), certB.key());
            assertTrue(reloader.checkNow());

            try(SSLSocket after = connect()) {
                assertEquals("CN=b", servedName(after));
                assertEquals("after", echo(after, "after"));
            }
            //the session established before the reload is unaffected
            assertEquals("before", echo(before, "before"));
        }
    }

    @Test
    public void unchangedFilesAreNotReloaded() throws Exception {
        reloader = new CertificateReloader(certFile, keyFile, 300);

        assertFalse(reloader.checkNow());
        //rewriting the same content is not a change
        write(certA.certificate(), certA.key());
        assertFalse(reloader.checkNow());
        assertEquals("CN=a", commonName(reloader.getKeyManager().getCertificate()));
    }

    @Test
    public void mismatchedKeyKeepsCurrentCertificateUntilFixed() throws Exception {
        reloader = new CertificateReloader(certFile, keyFile, 300);

        //the renewed certificate is written before its key
        write(certB.certificate(), certA.key());
        assertFalse(reloader.checkNow());
        assertEquals("CN=a", commonName(reloader.getKeyManager().getCertificate()));
        //the same failure is not retried until the files change again
        assertFalse(reloader.checkNow());

        write(certB.certificate(), certB.key());
        assertTrue(reloader.checkNow());
        assertEquals("CN=b", commonName(reloader.getKeyManager().getCertificate()));
    }

    @Test
    public void partlyWrittenCertificateKeepsCurrentCertificate() throws Exception {
        reloader = new CertificateReloader(certFile, keyFile, 300);

        write(certB.certificate().substring(0, certB.certificate().length() / 2), certB.key());
        assertFalse(reloader.checkNow());
        assertEquals("CN=a", commonName(reloader.getKeyManager().getCertificate()));

        write(certB.certificate(), certB.key());
        assertTrue(reloader.checkNow());
        assertEquals("CN=b", commonName(reloader.getKeyManager().getCertificate()));
    }

    @Test
    public void missingFileKeepsCurrentCertificate() throws Exception {
        reloader = new CertificateReloader(certFile, keyFile, 300);

        Files.delete(keyFile.toPath());
        assertFalse(reloader.checkNow());
        assertEquals("CN=a", commonName(reloader.getKeyManager().getCertificate()));

        write(certB.certificate(), certB.key());
        assertTrue(reloader.checkNow());
        assertEquals("CN=b", commonName(reloader.getKeyManager().getCertificate()));
    }

    @Test
    public void scheduledCheckReloadsChangedFiles() throws Exception {
        reloader = new CertificateReloader(certFile, keyFile, 1);
        reloader.start();

        write(certB.certificate(), certB.key());

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while(!"CN=b".equals(commonName(reloader.getKeyManager().getCertificate()))) {
            assertTrue(System.nanoTime() < deadline, "certificate not reloaded");
            Thread.sleep(50);
        }
    }

    @Test
    public void unexpectedExceptionDoesNotStopScheduledChecks() throws Exception {
        java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
        reloader = new CertificateReloader(certFile, keyFile, 1) {
            @Override
            public synchronized boolean checkNow() {
                if(checks.incrementAndGet() == 1) {
                    throw new IllegalStateException("unexpected");
                }
                return super.checkNow();
            }
        };
        reloader.start();

        write(certB.certificate(), certB.key());

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while(!"CN=b".equals(commonName(reloader.getKeyManager().getCertificate()))) {
            assertTrue(System.nanoTime() < deadline, "certificate not reloaded after an unexpected exception");
            Thread.sleep(50);
        }
        assertTrue(checks.get() >= 2);
    }

    @Test
    public void startupRequiresValidCertificate() throws Exception {
        write(certB.certificate(), certA.key());
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> new CertificateReloader(certFile, keyFile, 300));
        assertTrue(e.getMessage().contains("does not match"), e.getMessage());

        Files.delete(certFile.toPath());
        assertThrows(ConfigurationException.class, () -> new CertificateReloader(certFile, keyFile, 300));
    }

    @Test
    public void lookupsForReplacedAliasAreAnsweredByTheLoadThatIssuedIt() throws Exception {
        reloader = new CertificateReloader(certFile, keyFile, 300);
        ReloadingKeyManager keyManager = reloader.getKeyManager();
        //a handshake in progress chose its alias before the reload
        String aliasBefore = keyManager.chooseServerAlias("EC", null, null);

        write(certB.certificate(), certB.key());
        assertTrue(reloader.checkNow());

        //its chain and key still come from the certificate it chose, so they match each other
        assertEquals("CN=a", commonName(keyManager.getCertificateChain(aliasBefore)[0]));
        assertNotNull(keyManager.getPrivateKey(aliasBefore));
        String aliasAfter = keyManager.chooseServerAlias("EC", null, null);
        assertNotEquals(aliasBefore, aliasAfter);
        assertEquals("CN=b", commonName(keyManager.getCertificateChain(aliasAfter)[0]));
    }
}
