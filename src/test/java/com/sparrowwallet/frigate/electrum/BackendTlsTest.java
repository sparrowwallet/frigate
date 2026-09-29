package com.sparrowwallet.frigate.electrum;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import com.google.common.net.HostAndPort;
import com.sparrowwallet.frigate.ConfigurationException;
import com.sparrowwallet.frigate.io.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.sparrowwallet.frigate.electrum.FakeElectrumServer.*;
import static org.junit.jupiter.api.Assertions.*;

public class BackendTlsTest {
    private static final TomlMapper MAPPER = TomlMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    @TempDir
    static Path certDir;
    private static File certFileA;
    private static File certFileB;

    private FakeElectrumServer server;
    private ElectrumTransport transport;

    @BeforeAll
    public static void generateCertificates() throws Exception {
        certFileA = certDir.resolve("a.pem").toFile();
        certFileB = certDir.resolve("b.pem").toFile();
        TestCertificates.generate(certDir, "a").write(certFileA, certDir.resolve("a.key").toFile());
        TestCertificates.generate(certDir, "b").write(certFileB, certDir.resolve("b.key").toFile());
    }

    @BeforeEach
    public void setUp() throws IOException {
        //a TLS backend serving certificate A
        ReloadingKeyManager keyManager = new ReloadingKeyManager(certFileA, certDir.resolve("a.key").toFile());
        ServerSocket serverSocket = SslUtil.getServerSSLContext(keyManager).getServerSocketFactory().createServerSocket(0, 10, InetAddress.getLoopbackAddress());
        server = new FakeElectrumServer(serverSocket);
    }

    @AfterEach
    public void tearDown() throws IOException {
        if(transport != null) {
            transport.close();
        }
        server.close();
    }

    private void connect(BackendTls backendTls) throws IOException {
        transport = new ElectrumTransport(server.getHostAndPort(), Protocol.SSL, new Object(), 5000, backendTls);
        transport.connect();
        Thread.ofVirtual().start(transport::readInputLoop);
    }

    @Test
    public void pinnedCertificateIsAccepted() throws Exception {
        connect(BackendTls.pinned(certFileA));

        assertEquals(response("1", "\"ok\""), transport.pass(request(1, "server.ping")));
    }

    @Test
    public void differentCertificateFromPinnedIsRefused() {
        IOException e = assertThrows(IOException.class, () -> connect(BackendTls.pinned(certFileB)));

        assertFalse(transport.isConnected());
        assertTrue(e.getMessage().contains("Error connecting"), e.getMessage());
    }

    @Test
    public void verifyingRefusesSelfSignedCertificate() {
        assertThrows(IOException.class, () -> connect(BackendTls.verifying()));
        assertFalse(transport.isConnected());
    }

    @Test
    public void trustAllAcceptsSelfSignedCertificate() throws Exception {
        connect(BackendTls.trustAll());

        assertEquals(response("2", "\"ok\""), transport.pass(request(2, "server.ping")));
    }

    @Test
    public void invalidPinnedCertificateFailsAtStartup() throws Exception {
        assertThrows(ConfigurationException.class, () -> BackendTls.pinned(certDir.resolve("missing.pem").toFile()));

        File invalid = certDir.resolve("invalid.pem").toFile();
        Files.writeString(invalid.toPath(), "not a certificate");
        assertThrows(ConfigurationException.class, () -> BackendTls.pinned(invalid));
    }

    @Test
    public void prepareEnablesHostnameVerificationOnlyWhenVerifying() throws Exception {
        try(SSLSocket verifying = (SSLSocket)BackendTls.verifying().getSocketFactory().createSocket()) {
            BackendTls.verifying().prepare(verifying);
            assertEquals("HTTPS", verifying.getSSLParameters().getEndpointIdentificationAlgorithm());
        }

        try(SSLSocket pinned = (SSLSocket)BackendTls.pinned(certFileA).getSocketFactory().createSocket()) {
            BackendTls.pinned(certFileA).prepare(pinned);
            assertNull(pinned.getSSLParameters().getEndpointIdentificationAlgorithm());
        }
    }

    @Test
    public void serverNameIsSetByTheSocketFromTheConnectedHostname() throws Exception {
        byte[] loopback = InetAddress.getLoopbackAddress().getAddress();
        //the socket sends a fully qualified hostname as the server name
        assertEquals(List.of("electrum.example.com"), requestedServerNames(InetAddress.getByAddress("electrum.example.com", loopback)));
        //names that are not valid server names, such as Docker's electrs_1, are skipped rather than failing the connection
        assertEquals(List.of(), requestedServerNames(InetAddress.getByAddress("electrs_1", loopback)));
        assertEquals(List.of(), requestedServerNames(InetAddress.getByAddress("host.", loopback)));
        //as is an address
        assertEquals(List.of(), requestedServerNames(InetAddress.getByAddress(loopback)));
    }

    /**
     * Connects a prepared socket to a TLS server through an address carrying the given hostname, as the transport connects, and
     * returns the server names the server received. The hostname is attached to the address directly, so no DNS is needed.
     */
    private static List<String> requestedServerNames(InetAddress address) throws Exception {
        ReloadingKeyManager keyManager = new ReloadingKeyManager(certFileA, certDir.resolve("a.key").toFile());
        try(SSLServerSocket serverSocket = (SSLServerSocket)SslUtil.getServerSSLContext(keyManager).getServerSocketFactory().createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            java.util.concurrent.CompletableFuture<List<String>> names = new java.util.concurrent.CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try(SSLSocket socket = (SSLSocket)serverSocket.accept()) {
                    socket.startHandshake();
                    names.complete(((ExtendedSSLSession)socket.getSession()).getRequestedServerNames().stream()
                            .map(name -> ((SNIHostName)name).getAsciiName()).toList());
                } catch(Exception e) {
                    names.completeExceptionally(e);
                }
            });

            BackendTls backendTls = BackendTls.pinned(certFileA);
            try(SSLSocket socket = (SSLSocket)backendTls.getSocketFactory().createSocket()) {
                backendTls.prepare(socket);
                socket.connect(new java.net.InetSocketAddress(address, serverSocket.getLocalPort()), 5000);
                socket.startHandshake();
                return names.get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }

    @Test
    public void configSelectsMode() throws Exception {
        Config.ServerConfig pinnedAndVerify = MAPPER.readValue("[server]\nbackendElectrumServer = \"ssl://localhost:50002\"\nbackendSslVerify = true\nbackendSslCertFile = \""
                + certFileA.getAbsolutePath().replace("\\", "\\\\") + "\"\n", Config.class).getServer();
        assertEquals(BackendTls.Mode.PINNED, BackendTls.fromConfig(pinnedAndVerify).getMode());

        Config.ServerConfig verify = MAPPER.readValue("[server]\nbackendElectrumServer = \"ssl://localhost:50002\"\nbackendSslVerify = true\n", Config.class).getServer();
        assertEquals(BackendTls.Mode.VERIFYING, BackendTls.fromConfig(verify).getMode());

        Config.ServerConfig neither = MAPPER.readValue("[server]\nbackendElectrumServer = \"ssl://localhost:50002\"\n", Config.class).getServer();
        assertFalse(neither.getBackendSslVerify());
        assertNull(neither.getBackendSslCertFileObj());
        assertEquals(BackendTls.Mode.TRUST_ALL, BackendTls.fromConfig(neither).getMode());
    }

    @Test
    public void settingsForNonSslBackendAreIgnoredWithWarning() throws Exception {
        Logger logger = (Logger)LoggerFactory.getLogger(BackendTls.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            //a missing pinned certificate does not stop startup when the backend is not ssl://
            Config.ServerConfig pinnedTcp = MAPPER.readValue("[server]\nbackendElectrumServer = \"tcp://localhost:50001\"\nbackendSslCertFile = \"/missing/backend.pem\"\n", Config.class).getServer();
            assertEquals(BackendTls.Mode.TRUST_ALL, assertDoesNotThrow(() -> BackendTls.fromConfig(pinnedTcp)).getMode());

            Config.ServerConfig verifyTcp = MAPPER.readValue("[server]\nbackendElectrumServer = \"tcp://localhost:50001\"\nbackendSslVerify = true\n", Config.class).getServer();
            assertEquals(BackendTls.Mode.TRUST_ALL, BackendTls.fromConfig(verifyTcp).getMode());

            Config.ServerConfig verifyNoBackend = MAPPER.readValue("[server]\nbackendSslVerify = true\n", Config.class).getServer();
            assertEquals(BackendTls.Mode.TRUST_ALL, BackendTls.fromConfig(verifyNoBackend).getMode());

            //nothing set for a tcp:// backend: nothing to warn about
            BackendTls.fromConfig(MAPPER.readValue("[server]\nbackendElectrumServer = \"tcp://localhost:50001\"\n", Config.class).getServer());
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals(3, appender.list.size());
        assertTrue(appender.list.get(0).getFormattedMessage().contains("does not use SSL"));
        assertTrue(appender.list.get(1).getFormattedMessage().contains("does not use SSL"));
        assertTrue(appender.list.get(2).getFormattedMessage().contains("no backendElectrumServer"));
    }

    @Test
    public void unverifiedSslBackendIsWarnedAbout() throws Exception {
        Logger logger = (Logger)LoggerFactory.getLogger(BackendTls.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            BackendTls.fromConfig(MAPPER.readValue("[server]\nbackendElectrumServer = \"ssl://localhost:50002\"\n", Config.class).getServer());
            BackendTls.fromConfig(MAPPER.readValue("[server]\nbackendElectrumServer = \"tcp://localhost:50001\"\n", Config.class).getServer());
            BackendTls.fromConfig(MAPPER.readValue("[server]\nbackendElectrumServer = \"ssl://localhost:50002\"\nbackendSslVerify = true\n", Config.class).getServer());
        } finally {
            logger.detachAppender(appender);
        }

        //only the unverified ssl:// backend is warned about
        assertEquals(1, appender.list.size());
        assertTrue(appender.list.getFirst().getFormattedMessage().contains("without certificate verification"));
    }
}
