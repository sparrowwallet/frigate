package com.sparrowwallet.frigate.io;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Generates throwaway self-signed certificates for tests with the JDK's keytool, in the PEM formats Frigate reads.
 */
public final class TestCertificates {
    private static final String PASSWORD = "changeit";

    private TestCertificates() {
    }

    public record Pem(String certificate, String key) {
        /** Writes the certificate and key to the given files. */
        public void write(File certFile, File keyFile) throws IOException {
            Files.writeString(certFile.toPath(), certificate);
            Files.writeString(keyFile.toPath(), key);
        }
    }

    /**
     * @param dir a directory for the intermediate keystore
     * @param name the certificate's common name
     */
    public static Pem generate(Path dir, String name) throws Exception {
        File keystore = dir.resolve(name + ".p12").toFile();
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", name, "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=" + name,
                "-validity", "1", "-storetype", "PKCS12", "-keystore", keystore.getAbsolutePath(), "-storepass", PASSWORD, "-keypass", PASSWORD)
                .redirectErrorStream(true).start();
        if(!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("keytool failed: " + new String(process.getInputStream().readAllBytes()));
        }

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try(InputStream in = new FileInputStream(keystore)) {
            keyStore.load(in, PASSWORD.toCharArray());
        }
        Base64.Encoder encoder = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));
        String certificate = "-----BEGIN CERTIFICATE-----\n" + encoder.encodeToString(keyStore.getCertificate(name).getEncoded()) + "\n-----END CERTIFICATE-----\n";
        String key = "-----BEGIN PRIVATE KEY-----\n" + encoder.encodeToString(keyStore.getKey(name, PASSWORD.toCharArray()).getEncoded()) + "\n-----END PRIVATE KEY-----\n";
        return new Pem(certificate, key);
    }
}
