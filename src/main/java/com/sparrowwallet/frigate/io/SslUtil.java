package com.sparrowwallet.frigate.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

public final class SslUtil {
    private static final Logger log = LoggerFactory.getLogger(SslUtil.class);

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
}
