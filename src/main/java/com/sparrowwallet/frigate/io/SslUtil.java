package com.sparrowwallet.frigate.io;

import com.sparrowwallet.frigate.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
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

    public static SSLContext getServerSSLContext(File certFile, File keyFile) {
        if(!certFile.isFile()) {
            throw new ConfigurationException("SSL: certificate file not found: " + certFile.getAbsolutePath());
        }
        if(!keyFile.isFile()) {
            throw new ConfigurationException("SSL: private key file not found: " + keyFile.getAbsolutePath());
        }

        X509Certificate[] chain = readCertificateChain(certFile);
        PrivateKey privateKey = readPrivateKey(keyFile);

        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, new char[0]);
            keyStore.setKeyEntry("frigate", privateKey, new char[0], chain);

            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(keyStore, new char[0]);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), null, null);
            return sslContext;
        } catch(Exception e) {
            throw new ConfigurationException("SSL: failed to initialise TLS context: " + e.getMessage(), e);
        }
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
