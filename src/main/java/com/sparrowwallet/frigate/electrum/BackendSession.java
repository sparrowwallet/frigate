package com.sparrowwallet.frigate.electrum;

import com.github.arteam.simplejsonrpc.client.JsonRpcClient;
import com.github.arteam.simplejsonrpc.client.exception.JsonRpcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Owns one client session's connection to the backend Electrum server, keeping it connected for the life of the session.
 *
 * Invariant: the backend connection's lifecycle is the session's lifecycle. The connection is made for this session alone,
 * never pooled or shared, and closed with it, so a client disconnect is a single socket close that lets the backend free all of
 * that session's subscription state.
 *
 * A supervisor thread connects, starts a reader for each connection, and when a connection is lost reconnects with exponential
 * backoff and jitter. After each reconnect it negotiates the client's protocol version again, then resubscribes the session's
 * scripthashes, forwarding any status that changed while the connection was down so a wallet left open does not miss it.
 * While disconnected, proxied requests fail fast with BackendUnavailableException. An idle connection is kept alive with
 * server.ping, which also detects a backend that has stopped responding: a ping that times out closes the connection.
 */
public class BackendSession implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(BackendSession.class);

    public static final long INITIAL_BACKOFF_MILLIS = 1_000;

    private final String name;
    private final ElectrumTransport transport;
    private final ElectrumBackendService backendService;
    private final ScriptHashSubscriptions scriptHashSubscriptions;
    private final AsyncNotifier notifier;
    private final Supplier<VersionRequest> versionRequest;
    private final Settings settings;

    private final CountDownLatch firstAttempt = new CountDownLatch(1);
    private volatile boolean closed;
    private volatile Thread supervisor;
    private volatile int connectCount;

    /**
     * @param versionRequest the server.version request the client last had forwarded to the backend, or null if none yet
     */
    public BackendSession(String name, ElectrumTransport transport, ScriptHashSubscriptions scriptHashSubscriptions, AsyncNotifier notifier,
                          Supplier<VersionRequest> versionRequest, Settings settings) {
        this.name = name;
        this.transport = transport;
        this.backendService = new JsonRpcClient(transport).onDemand(ElectrumBackendService.class);
        this.scriptHashSubscriptions = scriptHashSubscriptions;
        this.notifier = notifier;
        this.versionRequest = versionRequest;
        this.settings = settings;
    }

    /**
     * Starts the supervisor, returning once the first connection attempt has completed, successfully or not, so that the
     * client's first requests are not failed while that attempt is still in progress.
     */
    public void start() throws InterruptedException {
        supervisor = Thread.ofVirtual().name(name).start(this::supervise);
        firstAttempt.await();
    }

    public boolean isConnected() {
        return transport.isConnected();
    }

    /**
     * @return the number of successful connections made, including the first
     */
    public int getConnectCount() {
        return connectCount;
    }

    private void supervise() {
        long backoffMillis = settings.initialBackoffMillis();
        boolean failing = false;
        try {
            while(!closed) {
                Thread reader = null;
                try {
                    transport.connect();
                    reader = Thread.ofVirtual().name(name + "-reader").start(transport::readInputLoop);
                    if(connectCount++ > 0) {
                        ServerMetrics.backendReconnected();
                    }
                    if(failing) {
                        log.info("Reconnected to backend Electrum server for " + name);
                    }
                    failing = false;
                    backoffMillis = settings.initialBackoffMillis();
                } catch(IOException | RuntimeException e) {
                    //an unexpected exception is treated as a failed attempt, so it cannot end the reconnect loop for good
                    if(!failing && !closed) {
                        log.warn(e.getMessage() + ", retrying");
                    } else {
                        log.debug(e.getMessage());
                    }
                    failing = true;
                } finally {
                    firstAttempt.countDown();
                }

                if(reader != null) {
                    restore();
                    keepAlive(reader);
                    if(!closed) {
                        Exception cause = transport.getLastException();
                        log.info("Connection to backend Electrum server lost for " + name + (cause == null ? "" : " (" + cause.getMessage() + ")") + ", reconnecting");
                    }
                }

                if(closed) {
                    return;
                }
                Thread.sleep(ThreadLocalRandom.current().nextLong(backoffMillis / 2, backoffMillis + 1));
                backoffMillis = Math.min(backoffMillis * 2, settings.maxBackoffMillis());
            }
        } catch(InterruptedException e) {
            //closed
        }
    }

    /**
     * Brings a new connection back to the session's state: the negotiated protocol version, then every scripthash subscription.
     * Runs on the supervisor thread while the connection's reader delivers the responses. A no-op on the first connection, as the
     * client has not yet negotiated or subscribed.
     */
    private void restore() {
        VersionRequest version = versionRequest.get();
        if(version != null) {
            try {
                backendService.getServerVersion(version.clientName(), version.protocolVersion());
            } catch(JsonRpcException e) {
                log.warn("Backend Electrum server rejected server.version on reconnect for " + name + ": " + e.getMessage());
            } catch(RuntimeException e) {
                log.debug("Could not negotiate version on reconnect for " + name + ": " + e.getMessage());
                return;
            }
        }

        int changed = 0;
        for(String scriptHash : scriptHashSubscriptions.getScriptHashes()) {
            if(closed || !transport.isConnected()) {
                return;
            }
            //a subscribe still awaiting its first response was sent on the lost connection, and fails back to the client
            if(!scriptHashSubscriptions.isSubscribed(scriptHash) || scriptHashSubscriptions.isPending(scriptHash)) {
                continue;
            }

            try {
                if(resubscribe(scriptHash)) {
                    changed++;
                }
            } catch(JsonRpcException e) {
                log.debug("Backend Electrum server rejected resubscribe for " + name + ": " + e.getMessage());
            } catch(RuntimeException e) {
                log.debug("Could not resubscribe for " + name + ": " + e.getMessage());
                return;
            }
        }

        if(changed > 0) {
            log.debug("Resubscribed " + name + " and delivered " + changed + " status changes missed while disconnected");
        }
    }

    /**
     * Resubscribes a scripthash on the new connection, and delivers its status to the client if it changed while the connection
     * was down. The scripthash is held in the notifier meanwhile, so a newer status notified on the new connection just after the
     * response cannot be delivered before this one and then be superseded by it; the notifier keeps whichever has the higher
     * read sequence.
     * @return true if the status changed
     */
    boolean resubscribe(String scriptHash) {
        String previous = scriptHashSubscriptions.getStatus(scriptHash);
        notifier.hold(scriptHash);
        try {
            String current = backendService.subscribeScriptHash(scriptHash);
            long sequence = ElectrumTransport.getReadSequence();
            scriptHashSubscriptions.recordSubscribeResponse(scriptHash, current, sequence);
            if(Objects.equals(previous, current)) {
                return false;
            }
            notifier.notifyScriptHash(scriptHash, current, sequence);
            return true;
        } finally {
            notifier.release(scriptHash);
        }
    }

    /**
     * Waits for the connection's reader to end, sending server.ping whenever the connection has been idle for the ping interval.
     */
    private void keepAlive(Thread reader) throws InterruptedException {
        long intervalNanos = TimeUnit.MILLISECONDS.toNanos(settings.pingIntervalMillis());
        while(reader.isAlive()) {
            long waitNanos = intervalNanos - transport.getIdleNanos();
            if(waitNanos > 0) {
                reader.join(Duration.ofNanos(waitNanos));
                continue;
            }

            try {
                backendService.ping();
            } catch(JsonRpcException e) {
                //the backend responded, which is all a keepalive needs
            } catch(RuntimeException e) {
                //usually the connection was lost or the ping timed out and closed it, so the reader is ending
                log.debug("Keepalive ping failed for " + name + ": " + e.getMessage());
                reader.join(Duration.ofNanos(intervalNanos));
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        firstAttempt.countDown();
        try {
            transport.close();
        } catch(IOException e) {
            log.debug("Error closing backend transport", e);
        }
        Thread current = supervisor;
        if(current != null) {
            current.interrupt();
        }
    }

    boolean awaitTermination(long millis) throws InterruptedException {
        Thread current = supervisor;
        return current == null || current.join(Duration.ofMillis(millis));
    }

    public record VersionRequest(String clientName, Object protocolVersion) {}

    /**
     * @param initialBackoffMillis the delay before the first reconnect attempt, doubling after each failure
     * @param maxBackoffMillis the maximum delay between reconnect attempts
     * @param pingIntervalMillis how long the connection may be idle before a keepalive ping is sent
     */
    public record Settings(long initialBackoffMillis, long maxBackoffMillis, long pingIntervalMillis) {}
}
