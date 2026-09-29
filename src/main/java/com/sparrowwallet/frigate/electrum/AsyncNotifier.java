package com.sparrowwallet.frigate.electrum;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Delivers a session's notifications on its own virtual thread, so a client that is slow to read blocks only itself rather than
 * the thread that produced the notification (the bitcoind poller, a silent payments scan, or the session's backend reader).
 *
 * Scripthash statuses are coalesced in an insertion-ordered map: a newer status for a pending scripthash replaces the older one
 * and keeps its place in the delivery order. The pending set is therefore bounded by the session's subscription count rather
 * than by how fast notifications arrive, and a client that stops reading is caught by the bound on other notifications
 * (headers, silent payments), which are queued in order, limited to queueSize, and delivered before pending statuses.
 * Exceeding that bound means the client is not keeping up, and the overflow handler is called once to disconnect the session.
 * Pending statuses are also checked against the subscription count plus queueSize, but only as a defensive bound: they are
 * for subscribed scripthashes, as statuses of unsubscribed scripthashes are discarded, and any queued in a race with an
 * unsubscribe are skipped at delivery.
 *
 * While a subscribe request for a scripthash is in progress, its statuses are held back until the response has been written
 * to the client (see hold()). Statuses read from the backend before the subscribe response are superseded by it and discarded;
 * those read after it are newer, and must reach the client after the response, not before. The backend session also holds a
 * scripthash while resubscribing it after a reconnect, for the same reason. Pending statuses are ordered by their backend read
 * sequence: a status never replaces a pending one with a higher sequence.
 */
public class AsyncNotifier {
    private static final Logger log = LoggerFactory.getLogger(AsyncNotifier.class);

    private final String name;
    private final int queueSize;
    private final ScriptHashSubscriptions scriptHashSubscriptions;
    private final BiConsumer<String, String> scriptHashWriter;
    private final Runnable overflowHandler;

    private final LinkedHashMap<String, PendingStatus> pendingStatus = new LinkedHashMap<>();
    private final ArrayDeque<Runnable> otherNotifications = new ArrayDeque<>();
    private final Map<String, Integer> held = new HashMap<>();
    private boolean closed;
    private volatile boolean delivering;
    private Thread thread;

    /**
     * @param scriptHashSubscriptions the session's subscriptions, whose count bounds the pending status map and which are checked at delivery
     * @param scriptHashWriter writes a scripthash status notification to the client
     * @param overflowHandler called at most once, off the notifier's lock, when the client is not keeping up
     */
    public AsyncNotifier(String name, int queueSize, ScriptHashSubscriptions scriptHashSubscriptions, BiConsumer<String, String> scriptHashWriter, Runnable overflowHandler) {
        this.name = name;
        this.queueSize = queueSize;
        this.scriptHashSubscriptions = scriptHashSubscriptions;
        this.scriptHashWriter = scriptHashWriter;
        this.overflowHandler = overflowHandler;
    }

    public synchronized void start() {
        if(thread == null && !closed) {
            thread = Thread.ofVirtual().name(name).start(this::drainLoop);
        }
    }

    /**
     * @param sequence the backend read sequence of the notification carrying this status, see ElectrumTransport.getReadSequence()
     */
    public void notifyScriptHash(String scriptHash, String status, long sequence) {
        boolean overflowed;
        synchronized(this) {
            if(closed) {
                return;
            }
            PendingStatus pending = pendingStatus.get(scriptHash);
            if(pending == null || pending.sequence() < sequence) {
                pendingStatus.put(scriptHash, new PendingStatus(status, sequence));
            }
            overflowed = pendingStatus.size() > scriptHashSubscriptions.size() + queueSize;
            ServerMetrics.notifierQueueDepth(pendingStatus.size() + otherNotifications.size());
            notifyAll();
        }

        if(overflowed) {
            overflow("pending scripthash statuses exceed subscription count");
        }
    }

    public void notify(Runnable notification) {
        boolean overflowed;
        synchronized(this) {
            if(closed) {
                return;
            }
            overflowed = otherNotifications.size() >= queueSize;
            if(!overflowed) {
                otherNotifications.add(notification);
                ServerMetrics.notifierQueueDepth(pendingStatus.size() + otherNotifications.size());
                notifyAll();
            }
        }

        if(overflowed) {
            overflow("notification queue full");
        }
    }

    /**
     * Discards a pending status for a scripthash the client has unsubscribed from, so it is not delivered afterwards.
     */
    public synchronized void discardScriptHash(String scriptHash) {
        pendingStatus.remove(scriptHash);
    }

    /**
     * Discards a pending status that is older than the backend message with the given read sequence. Used when the subscribe
     * response for a scripthash is recorded: a status read before that response is superseded by it, and delivering it after
     * the response would leave the client with an out of date status, while a status read after the response is newer and kept.
     */
    public synchronized void discardScriptHash(String scriptHash, long beforeSequence) {
        PendingStatus pending = pendingStatus.get(scriptHash);
        if(pending != null && pending.sequence() < beforeSequence) {
            pendingStatus.remove(scriptHash);
        }
    }

    /**
     * Holds back statuses for a scripthash whose subscribe is in progress, until release() is called for it: by the request
     * thread once the response to the client request containing the subscribe has been written, or by the backend session once
     * its resubscribe has been recorded. Holds are counted, as both may hold the same scripthash at once.
     */
    public synchronized void hold(String scriptHash) {
        held.merge(scriptHash, 1, Integer::sum);
    }

    public synchronized void release(String scriptHash) {
        Integer count = held.get(scriptHash);
        if(count == null) {
            return;
        }
        if(count > 1) {
            held.put(scriptHash, count - 1);
        } else {
            held.remove(scriptHash);
            notifyAll();
        }
    }

    /**
     * Stops delivery promptly: the drain thread wakes and exits, and undelivered notifications are discarded.
     */
    public synchronized void close() {
        closed = true;
        pendingStatus.clear();
        otherNotifications.clear();
        held.clear();
        notifyAll();
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    /**
     * @return true while the drain thread is writing a notification to the client. Once closed, no new write starts, so if this
     * is false after close() the drain thread cannot be holding the client socket's write lock.
     */
    public boolean isDelivering() {
        return delivering;
    }

    /**
     * @return the number of notifications waiting to be delivered
     */
    public synchronized int getPendingCount() {
        return pendingStatus.size() + otherNotifications.size();
    }

    boolean awaitTermination(long millis) throws InterruptedException {
        Thread drainThread;
        synchronized(this) {
            drainThread = thread;
        }
        return drainThread == null || drainThread.join(Duration.ofMillis(millis));
    }

    private void overflow(String reason) {
        synchronized(this) {
            if(closed) {
                return;
            }
            close();
        }

        log.warn("Disconnecting slow client {}: {}", name, reason);
        overflowHandler.run();
    }

    private void drainLoop() {
        while(true) {
            Runnable notification = null;
            String scriptHash = null;
            String status = null;
            synchronized(this) {
                while(!closed && otherNotifications.isEmpty() && !hasDeliverableStatus()) {
                    try {
                        wait();
                    } catch(InterruptedException e) {
                        return;
                    }
                }
                if(closed) {
                    return;
                }
                //headers and silent payments first, so a new tip precedes the statuses it caused
                if(!otherNotifications.isEmpty()) {
                    notification = otherNotifications.poll();
                } else {
                    Map.Entry<String, PendingStatus> pending = pollDeliverableStatus();
                    scriptHash = pending.getKey();
                    status = pending.getValue().status();
                    if(!scriptHashSubscriptions.isSubscribed(scriptHash)) {
                        //queued in a race with an unsubscribe, after its discard
                        continue;
                    }
                }
                delivering = true;
            }

            try {
                if(notification != null) {
                    notification.run();
                } else {
                    scriptHashWriter.accept(scriptHash, status);
                }
                ServerMetrics.notificationDelivered();
            } catch(Exception e) {
                log.error("Error delivering notification", e);
            } finally {
                delivering = false;
            }
        }
    }

    private boolean hasDeliverableStatus() {
        if(held.isEmpty()) {
            return !pendingStatus.isEmpty();
        }
        for(String scriptHash : pendingStatus.keySet()) {
            if(!held.containsKey(scriptHash)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes and returns the oldest pending status that is not held. Held scripthashes are few (those in one client request,
     * and the one being resubscribed), so skipping past them is cheap.
     */
    private Map.Entry<String, PendingStatus> pollDeliverableStatus() {
        if(held.isEmpty()) {
            return pendingStatus.pollFirstEntry();
        }
        Iterator<Map.Entry<String, PendingStatus>> iter = pendingStatus.entrySet().iterator();
        while(iter.hasNext()) {
            Map.Entry<String, PendingStatus> entry = iter.next();
            if(!held.containsKey(entry.getKey())) {
                iter.remove();
                return entry;
            }
        }
        throw new IllegalStateException("No deliverable status");
    }

    private record PendingStatus(String status, long sequence) {}
}
