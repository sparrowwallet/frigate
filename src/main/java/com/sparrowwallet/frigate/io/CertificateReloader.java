package com.sparrowwallet.frigate.io;

import com.sparrowwallet.frigate.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Serves the TLS certificate and key from their files, reloading them when they change so a renewed certificate (from Let's
 * Encrypt, for example) is picked up without a restart. The files are checked periodically, and a change is detected by their
 * content rather than their modification time, so a rewrite within the same second is not missed.
 *
 * A reload that fails (files part way through being replaced, a key that does not match the certificate, a malformed file)
 * leaves the current certificate in service and is retried when the files change again.
 */
public class CertificateReloader implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(CertificateReloader.class);

    private final File certFile;
    private final File keyFile;
    private final long intervalSeconds;
    private final ReloadingKeyManager keyManager;
    private byte[] loadedFingerprint;
    private byte[] failedFingerprint;
    private ScheduledExecutorService executor;

    /**
     * Loads the certificate and key.
     * @throws ConfigurationException if they cannot be loaded, so a server does not start without a valid certificate
     */
    public CertificateReloader(File certFile, File keyFile, long intervalSeconds) {
        this.certFile = certFile;
        this.keyFile = keyFile;
        this.intervalSeconds = intervalSeconds;
        //fingerprinted before loading, so a change during the initial load is seen by the first check
        byte[] fingerprint = fingerprint();
        this.keyManager = new ReloadingKeyManager(certFile, keyFile);
        this.loadedFingerprint = fingerprint;
    }

    public ReloadingKeyManager getKeyManager() {
        return keyManager;
    }

    public synchronized void start() {
        if(executor == null) {
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "CertificateReloader");
                thread.setDaemon(true);
                return thread;
            });
            executor.scheduleWithFixedDelay(this::scheduledCheck, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        }
    }

    /**
     * A scheduled task that throws is never run again, and its exception is held in a future nobody reads, which would silently
     * stop reloading while the served certificate heads towards expiry. So nothing is allowed to escape, and checks continue.
     */
    private void scheduledCheck() {
        try {
            checkNow();
        } catch(Throwable t) {
            log.error("Unexpected error checking the TLS certificate for changes, will check again in " + intervalSeconds + "s", t);
        }
    }

    /**
     * Reloads the certificate and key if their files have changed since they were last loaded.
     * @return true if a new certificate was loaded
     */
    public synchronized boolean checkNow() {
        byte[] fingerprint = fingerprint();
        if(fingerprint == null) {
            if(failedFingerprint == null || failedFingerprint.length > 0) {
                log.warn("Cannot read TLS certificate " + certFile.getAbsolutePath() + " or key " + keyFile.getAbsolutePath() + ", keeping the current certificate");
                failedFingerprint = new byte[0];
            }
            return false;
        }
        if(Arrays.equals(fingerprint, loadedFingerprint)) {
            failedFingerprint = null;
            return false;
        }
        if(Arrays.equals(fingerprint, failedFingerprint)) {
            return false;
        }

        try {
            X509Certificate certificate = keyManager.reload(certFile, keyFile);
            loadedFingerprint = fingerprint;
            failedFingerprint = null;
            log.info("Reloaded TLS certificate for " + certificate.getSubjectX500Principal().getName() + ", valid until " + certificate.getNotAfter().toInstant());
            return true;
        } catch(RuntimeException e) {
            //a ConfigurationException for invalid files, or an unexpected exception from certificate or key parsing
            failedFingerprint = fingerprint;
            String reason = e instanceof ConfigurationException ? e.getMessage() : e.toString();
            log.warn("Could not reload TLS certificate, keeping the current certificate: " + reason);
            return false;
        }
    }

    /**
     * @return a hash of the certificate and key files' contents, or null if either cannot be read
     */
    private byte[] fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] cert = Files.readAllBytes(certFile.toPath());
            byte[] key = Files.readAllBytes(keyFile.toPath());
            digest.update(Integer.toString(cert.length).getBytes());
            digest.update(cert);
            digest.update(key);
            return digest.digest();
        } catch(IOException e) {
            return null;
        } catch(NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public synchronized void close() {
        if(executor != null) {
            executor.shutdownNow();
        }
    }
}
