package com.sparrowwallet.frigate.electrum;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Delivers new chain tips only to the sessions that have called blockchain.headers.subscribe, replacing the EventBus
 * broadcast that every session received and filtered. Sessions register on subscribe and deregister on disconnect.
 */
public class HeadersDispatcher {
    private static final Logger log = LoggerFactory.getLogger(HeadersDispatcher.class);

    private final Set<Subscriber> subscribers = ConcurrentHashMap.newKeySet();

    public void subscribe(Subscriber subscriber) {
        subscribers.add(subscriber);
    }

    public void unsubscribe(Subscriber subscriber) {
        subscribers.remove(subscriber);
    }

    public int getSubscriberCount() {
        return subscribers.size();
    }

    public void notify(ElectrumBlockHeader header) {
        for(Subscriber subscriber : subscribers) {
            try {
                subscriber.notifyHeaders(header);
            } catch(Exception e) {
                log.error("Error delivering headers notification", e);
            }
        }
    }

    public interface Subscriber {
        void notifyHeaders(ElectrumBlockHeader header);
    }
}
