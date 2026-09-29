package com.sparrowwallet.frigate.electrum;

import java.util.concurrent.ConcurrentHashMap;

/**
 * A session's scripthash subscriptions, each mapped to the last status recorded for it (from the subscribe response or a
 * forwarded notification). The recorded status lets a reconnected backend connection detect changes missed while it was down.
 *
 * ConcurrentHashMap cannot hold null values, so the null status of a scripthash with no history is stored as a sentinel,
 * as is the not-yet-known status of a subscription whose backend response has not arrived. Neither sentinel is a valid
 * (64 character hex) status.
 */
public class ScriptHashSubscriptions {
    private static final String NO_HISTORY = "-";
    private static final String PENDING = "?";

    private final ConcurrentHashMap<String, String> statuses = new ConcurrentHashMap<>();

    /**
     * Adds a subscription before its backend request is sent, so a notification that races the response is not dropped.
     * An existing subscription keeps its recorded status.
     */
    public void subscribe(String scriptHash) {
        statuses.putIfAbsent(scriptHash, PENDING);
    }

    /**
     * Records the status returned by the backend subscribe response. Only a pending subscription is updated: the response is read
     * off the backend connection before any later notification, but may be recorded after it, and the notification's status is newer.
     */
    public void recordSubscribeResponse(String scriptHash, String status) {
        statuses.replace(scriptHash, PENDING, encode(status));
    }

    /**
     * Records the status carried by a backend notification.
     * @return true if the scripthash is subscribed and the notification should be forwarded to the client
     */
    public boolean recordNotification(String scriptHash, String status) {
        return statuses.computeIfPresent(scriptHash, (key, previous) -> encode(status)) != null;
    }

    public boolean unsubscribe(String scriptHash) {
        return statuses.remove(scriptHash) != null;
    }

    public boolean isSubscribed(String scriptHash) {
        return statuses.containsKey(scriptHash);
    }

    public boolean isPending(String scriptHash) {
        return PENDING.equals(statuses.get(scriptHash));
    }

    /**
     * @return the last recorded status, which is null both for a scripthash with no history and for one that is not subscribed
     * or still pending - use isSubscribed and isPending to distinguish these
     */
    public String getStatus(String scriptHash) {
        String status = statuses.get(scriptHash);
        return status == null || status.equals(PENDING) ? null : decode(status);
    }

    public int size() {
        return statuses.size();
    }

    private static String encode(String status) {
        return status == null ? NO_HISTORY : status;
    }

    private static String decode(String status) {
        return status.equals(NO_HISTORY) ? null : status;
    }
}
