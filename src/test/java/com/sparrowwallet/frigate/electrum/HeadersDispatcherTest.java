package com.sparrowwallet.frigate.electrum;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class HeadersDispatcherTest {
    private static final ElectrumBlockHeader HEADER = new ElectrumBlockHeader(100, "00");

    @Test
    public void deliversOnlyToSubscribers() {
        HeadersDispatcher dispatcher = new HeadersDispatcher();
        RecordingSubscriber subscribed = new RecordingSubscriber();
        RecordingSubscriber notSubscribed = new RecordingSubscriber();
        dispatcher.subscribe(subscribed);

        dispatcher.notify(HEADER);

        assertEquals(List.of(HEADER), subscribed.received);
        assertTrue(notSubscribed.received.isEmpty());
    }

    @Test
    public void unsubscribeStopsDelivery() {
        HeadersDispatcher dispatcher = new HeadersDispatcher();
        RecordingSubscriber subscriber = new RecordingSubscriber();
        dispatcher.subscribe(subscriber);
        dispatcher.unsubscribe(subscriber);

        dispatcher.notify(HEADER);

        assertTrue(subscriber.received.isEmpty());
        assertEquals(0, dispatcher.getSubscriberCount());
    }

    @Test
    public void repeatedSubscribeDeliversOnce() {
        HeadersDispatcher dispatcher = new HeadersDispatcher();
        RecordingSubscriber subscriber = new RecordingSubscriber();
        dispatcher.subscribe(subscriber);
        dispatcher.subscribe(subscriber);

        dispatcher.notify(HEADER);

        assertEquals(List.of(HEADER), subscriber.received);
    }

    @Test
    public void failingSubscriberDoesNotBlockOthers() {
        HeadersDispatcher dispatcher = new HeadersDispatcher();
        RecordingSubscriber healthy = new RecordingSubscriber();
        dispatcher.subscribe(header -> {
            throw new IllegalStateException("delivery failed");
        });
        dispatcher.subscribe(healthy);

        assertDoesNotThrow(() -> dispatcher.notify(HEADER));
        assertEquals(List.of(HEADER), healthy.received);
    }

    private static class RecordingSubscriber implements HeadersDispatcher.Subscriber {
        private final List<ElectrumBlockHeader> received = new ArrayList<>();

        @Override
        public void notifyHeaders(ElectrumBlockHeader header) {
            received.add(header);
        }
    }
}
