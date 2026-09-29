package com.sparrowwallet.frigate.electrum;

import java.util.concurrent.ConcurrentHashMap;

/**
 * A session's scripthash subscriptions, each mapped to the last status recorded for it (from the subscribe response or a
 * forwarded notification). The recorded status lets a reconnected backend connection detect changes missed while it was down.
 *
 * Each status is recorded with the backend read sequence of the message that carried it (see ElectrumTransport.getReadSequence()),
 * and a status only replaces one with a lower sequence. The subscribe response is recorded by the request thread after pass()
 * returns, while notifications read after that response are recorded by the backend reader thread, so without the sequence an
 * older response status could overwrite a newer notification status.
 */
public class ScriptHashSubscriptions {
    private static final RecordedStatus PENDING = new RecordedStatus(null, Long.MIN_VALUE);

    private final ConcurrentHashMap<String, RecordedStatus> statuses = new ConcurrentHashMap<>();

    /**
     * Adds a subscription before its backend request is sent, so a notification that races the response is not dropped.
     * An existing subscription keeps its recorded status.
     */
    public void subscribe(String scriptHash) {
        statuses.putIfAbsent(scriptHash, PENDING);
    }

    /**
     * Records the status returned by the backend subscribe response, unless a newer status has already been recorded.
     */
    public void recordSubscribeResponse(String scriptHash, String status, long sequence) {
        record(scriptHash, status, sequence);
    }

    /**
     * Records the status carried by a backend notification, unless a newer status has already been recorded.
     * @return true if the scripthash is subscribed and the notification should be forwarded to the client
     */
    public boolean recordNotification(String scriptHash, String status, long sequence) {
        return record(scriptHash, status, sequence);
    }

    private boolean record(String scriptHash, String status, long sequence) {
        return statuses.computeIfPresent(scriptHash, (key, previous) -> sequence > previous.sequence() ? new RecordedStatus(status, sequence) : previous) != null;
    }

    public boolean unsubscribe(String scriptHash) {
        return statuses.remove(scriptHash) != null;
    }

    public boolean isSubscribed(String scriptHash) {
        return statuses.containsKey(scriptHash);
    }

    public boolean isPending(String scriptHash) {
        return statuses.get(scriptHash) == PENDING;
    }

    /**
     * @return the last recorded status, which is null both for a scripthash with no history and for one that is not subscribed
     * or still pending - use isSubscribed and isPending to distinguish these
     */
    public String getStatus(String scriptHash) {
        RecordedStatus recorded = statuses.get(scriptHash);
        return recorded == null ? null : recorded.status();
    }

    public int size() {
        return statuses.size();
    }

    private record RecordedStatus(String status, long sequence) {}
}
