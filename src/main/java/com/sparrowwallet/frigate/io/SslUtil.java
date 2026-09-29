package com.sparrowwallet.frigate.io;

import com.sparrowwallet.frigate.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SslUtil {
    private static final Logger log = LoggerFactory.getLogger(SslUtil.class);

    private static final Pattern PEM_BLOCK = Pattern.compile("-----BEGIN ([A-Z0-9 ]+?)-----\\s*([A-Za-z0-9+/=\\s]+?)-----END \\1-----", Pattern.DOTALL);
    private static final List<String> KEY_FACTORY_ALGORITHMS = List.of("RSA", "EC", "DSA");

    private SslUtil() {}

    public static SSLSocketFactory getTrustAllSocketFactory() {
        TrustManager[] trustAllCerts = new TrustManager[] {
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }

                    public void checkClientTrusted(X509Certificate[] certs, String authType) throws CertificateException {
                    }

                    public void checkServerTrusted(X509Certificate[] certs, String authType) throws CertificateException {
                    }
                }
        };

        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, null);
            return sslContext.getSocketFactory();
        } catch(Exception e) {
            log.error("Error creating SSL socket factory", e);
        }

        return null;
    }

    /**
     * Creates the server's TLS context around the given key manager, which supplies the certificate and key for each handshake.
     */
    public static SSLContext getServerSSLContext(X509ExtendedKeyManager keyManager) {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(new KeyManager[] {keyManager}, null, null);
            return sslContext;
        } catch(Exception e) {
            throw new ConfigurationException("SSL: failed to initialise TLS context: " + e.getMessage(), e);
        }
    }

    /**
     * Loads a PEM certificate chain and PKCS#8 private key into a key manager holding them under the given alias, checking that
     * the key belongs to the certificate.
     * @throws ConfigurationException if either file is missing or invalid, or the key does not match the certificate
     */
    public static X509ExtendedKeyManager loadKeyManager(File certFile, File keyFile, String alias) {
        if(!certFile.isFile()) {
            throw new ConfigurationException("SSL: certificate file not found: " + certFile.getAbsolutePath());
        }
        if(!keyFile.isFile()) {
            throw new ConfigurationException("SSL: private key file not found: " + keyFile.getAbsolutePath());
        }

        X509Certificate[] chain = readCertificateChain(certFile);
        PrivateKey privateKey = readPrivateKey(keyFile);
        checkKeyMatchesCertificate(privateKey, chain[0], certFile, keyFile);

        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, new char[0]);
            keyStore.setKeyEntry(alias, privateKey, new char[0], chain);

            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(keyStore, new char[0]);
            for(KeyManager keyManager : kmf.getKeyManagers()) {
                if(keyManager instanceof X509ExtendedKeyManager x509KeyManager) {
                    return x509KeyManager;
                }
            }
            throw new ConfigurationException("SSL: no X.509 key manager available");
        } catch(ConfigurationException e) {
            throw e;
        } catch(Exception e) {
            throw new ConfigurationException("SSL: failed to load certificate and key: " + e.getMessage(), e);
        }
    }

    /**
     * Checks the private key belongs to the certificate by signing test data with the key and verifying it with the certificate.
     * A certificate renewal can briefly leave a new certificate beside the old key, which would otherwise only fail at handshake.
     */
    private static void checkKeyMatchesCertificate(PrivateKey privateKey, X509Certificate certificate, File certFile, File keyFile) {
        String algorithm = switch(privateKey.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            case "DSA" -> "SHA256withDSA";
            default -> privateKey.getAlgorithm();
        };

        try {
            byte[] data = "frigate key check".getBytes(StandardCharsets.UTF_8);
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign(privateKey);
            signer.update(data);
            byte[] signature = signer.sign();

            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(certificate.getPublicKey());
            verifier.update(data);
            if(verifier.verify(signature)) {
                return;
            }
        } catch(GeneralSecurityException e) {
            //a key of a different type to the certificate's also lands here
            log.debug("Key check failed", e);
        }

        throw new ConfigurationException("SSL: private key " + keyFile.getAbsolutePath() + " does not match certificate " + certFile.getAbsolutePath());
    }

    private static X509Certificate[] readCertificateChain(File certFile) {
        try(FileInputStream fis = new FileInputStream(certFile);
            BufferedInputStream bis = new BufferedInputStream(fis)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> certs = cf.generateCertificates(bis);

            if(certs.isEmpty()) {
                throw new ConfigurationException("SSL: no certificates found in " + certFile.getAbsolutePath());
            }

            X509Certificate[] chain = new X509Certificate[certs.size()];
            int i = 0;
            for(Certificate c : certs) {
                chain[i++] = (X509Certificate)c;
            }

            return chain;
        } catch(IOException | CertificateException e) {
            throw new ConfigurationException("SSL: failed to parse certificate " + certFile.getAbsolutePath() + ": " + e.getMessage(), e);
        }
    }

    private static PrivateKey readPrivateKey(File keyFile) {
        String pem;
        try {
            pem = Files.readString(keyFile.toPath(), StandardCharsets.UTF_8);
        } catch(IOException e) {
            throw new ConfigurationException("SSL: failed to read private key " + keyFile.getAbsolutePath() + ": " + e.getMessage(), e);
        }

        Matcher m = PEM_BLOCK.matcher(pem);
        if(!m.find()) {
            throw new ConfigurationException("SSL: no PEM block found in " + keyFile.getAbsolutePath());
        }
        String label = m.group(1).trim();
        if(!"PRIVATE KEY".equals(label)) {
            throw new ConfigurationException("SSL: unsupported key format '" + label + "' in " + keyFile.getAbsolutePath()
                    + ". Only unencrypted PKCS#8 ('-----BEGIN PRIVATE KEY-----') is supported. Convert with: "
                    + "openssl pkcs8 -topk8 -nocrypt -in <key> -out <pkcs8-key>");
        }

        byte[] der;
        try {
            der = Base64.getMimeDecoder().decode(m.group(2));
        } catch(IllegalArgumentException e) {
            throw new ConfigurationException("SSL: malformed base64 in private key " + keyFile.getAbsolutePath(), e);
        }

        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        InvalidKeySpecException lastException = null;
        for(String algorithm : KEY_FACTORY_ALGORITHMS) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch(InvalidKeySpecException e) {
                lastException = e;
            } catch(Exception e) {
                throw new ConfigurationException("SSL: failed to load private key " + keyFile.getAbsolutePath() + ": " + e.getMessage(), e);
            }
        }

        throw new ConfigurationException("SSL: unrecognised private key algorithm in " + keyFile.getAbsolutePath() + " (tried " + KEY_FACTORY_ALGORITHMS + ")", lastException);
    }
}
