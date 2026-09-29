package com.sparrowwallet.frigate.io;

import com.sparrowwallet.frigate.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.File;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;

/**
 * How the connection to an ssl:// backend Electrum server is authenticated, configured once at startup:
 * <ul>
 * <li>pinned: the server must present exactly the configured certificate, the Electrum ecosystem norm for self-signed servers</li>
 * <li>verifying: standard certificate authority validation and hostname verification, for a remote server with a public certificate</li>
 * <li>trust all: any certificate is accepted, suitable only for a backend on localhost or a trusted network</li>
 * </ul>
 */
public final class BackendTls {
    private static final Logger log = LoggerFactory.getLogger(BackendTls.class);

    public enum Mode { TRUST_ALL, VERIFYING, PINNED }

    private final Mode mode;
    private final SSLSocketFactory socketFactory;

    private BackendTls(Mode mode, SSLSocketFactory socketFactory) {
        this.mode = mode;
        this.socketFactory = socketFactory;
    }

    public static BackendTls trustAll() {
        return new BackendTls(Mode.TRUST_ALL, SslUtil.getTrustAllSocketFactory());
    }

    public static BackendTls verifying() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, null, null);
            return new BackendTls(Mode.VERIFYING, sslContext.getSocketFactory());
        } catch(Exception e) {
            throw new ConfigurationException("SSL: failed to initialise backend TLS verification: " + e.getMessage(), e);
        }
    }

    /**
     * @param certFile a PEM file whose first certificate the backend must present
     * @throws ConfigurationException if the file cannot be read
     */
    public static BackendTls pinned(File certFile) {
        if(!certFile.isFile()) {
            throw new ConfigurationException("SSL: backend certificate file not found: " + certFile.getAbsolutePath());
        }
        X509Certificate pinned = SslUtil.readCertificateChain(certFile)[0];
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[] {new PinnedTrustManager(pinned)}, null);
            return new BackendTls(Mode.PINNED, sslContext.getSocketFactory());
        } catch(Exception e) {
            throw new ConfigurationException("SSL: failed to initialise backend certificate pinning: " + e.getMessage(), e);
        }
    }

    /**
     * Pinning takes precedence over verification. The settings only apply to an ssl:// backend: otherwise they are ignored with a
     * warning, and a pinned certificate file is not loaded, so it cannot stop startup. Logs a warning when an ssl:// backend is
     * neither pinned nor verified.
     */
    public static BackendTls fromConfig(Config.ServerConfig config) {
        File pinnedCertFile = config.getBackendSslCertFileObj();
        boolean verify = config.getBackendSslVerify();
        Server backend = config.getBackendElectrumServerObj();

        if(backend == null || backend.getProtocol() != Protocol.SSL) {
            if(pinnedCertFile != null || verify) {
                log.warn("backendSslCertFile and backendSslVerify have no effect, as " + (backend == null ? "no backendElectrumServer is configured"
                        : "backend Electrum server " + backend.getUrl() + " does not use SSL"));
            }
            return trustAll();
        }

        if(pinnedCertFile != null) {
            return pinned(pinnedCertFile);
        }
        if(verify) {
            return verifying();
        }

        log.warn("Backend Electrum server " + backend.getUrl() + " uses SSL without certificate verification: set backendSslCertFile to pin its certificate, or backendSslVerify = true");
        return trustAll();
    }

    public Mode getMode() {
        return mode;
    }

    public SSLSocketFactory getSocketFactory() {
        return socketFactory;
    }

    /**
     * Prepares a socket before it connects, enabling hostname verification when verifying. The server name indication needs no
     * setting: on connect, the socket sets it from the hostname it was connected with, skipping names that are not valid server
     * names (such as Docker-style names containing '_'), and uses the same hostname for verification.
     */
    public void prepare(SSLSocket socket) {
        if(mode == Mode.VERIFYING) {
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
        }
    }

    /**
     * Trusts exactly one server certificate, compared by its encoding. Certificate authorities and hostnames are not considered.
     */
    private static class PinnedTrustManager implements X509TrustManager {
        private final X509Certificate pinned;

        PinnedTrustManager(X509Certificate pinned) {
            this.pinned = pinned;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                if(chain == null || chain.length == 0 || !Arrays.equals(chain[0].getEncoded(), pinned.getEncoded())) {
                    throw new CertificateException("Backend Electrum server certificate does not match the pinned certificate");
                }
            } catch(CertificateEncodingException e) {
                throw new CertificateException("Could not compare backend certificate with the pinned certificate", e);
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("Client certificates are not trusted");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
