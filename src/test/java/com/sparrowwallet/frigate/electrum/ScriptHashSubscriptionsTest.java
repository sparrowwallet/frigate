package com.sparrowwallet.frigate.electrum;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ScriptHashSubscriptionsTest {
    private static final String SCRIPT_HASH = "8b01df4e368ea28f8dc0423bcf7a4923e3a12d307c875e47a0cfbf90b5c39161";
    private static final String STATUS_1 = "a1".repeat(32);
    private static final String STATUS_2 = "b2".repeat(32);

    @Test
    public void subscribeResponseRecordsStatus() {
        ScriptHashSubscriptions subscriptions = new ScriptHashSubscriptions();
        subscriptions.subscribe(SCRIPT_HASH);
        assertTrue(subscriptions.isSubscribed(SCRIPT_HASH));
        assertTrue(subscriptions.isPending(SCRIPT_HASH));

        subscriptions.recordSubscribeResponse(SCRIPT_HASH, STATUS_1, 1);

        assertFalse(subscriptions.isPending(SCRIPT_HASH));
        assertEquals(STATUS_1, subscriptions.getStatus(SCRIPT_HASH));
    }

    @Test
    public void nullStatusIsRecorded() {
        ScriptHashSubscriptions subscriptions = new ScriptHashSubscriptions();
        subscriptions.subscribe(SCRIPT_HASH);

        subscriptions.recordSubscribeResponse(SCRIPT_HASH, null, 1);

        assertTrue(subscriptions.isSubscribed(SCRIPT_HASH));
        assertFalse(subscriptions.isPending(SCRIPT_HASH));
        assertNull(subscriptions.getStatus(SCRIPT_HASH));

        assertTrue(subscriptions.recordNotification(SCRIPT_HASH, STATUS_1, 2));
        assertTrue(subscriptions.recordNotification(SCRIPT_HASH, null, 3));
        assertNull(subscriptions.getStatus(SCRIPT_HASH));
    }

    @Test
    public void notificationUpdatesStatus() {
        ScriptHashSubscriptions subscriptions = new ScriptHashSubscriptions();
        subscriptions.subscribe(SCRIPT_HASH);
        subscriptions.recordSubscribeResponse(SCRIPT_HASH, STATUS_1, 1);

        assertTrue(subscriptions.recordNotification(SCRIPT_HASH, STATUS_2, 2));

        assertEquals(STATUS_2, subscriptions.getStatus(SCRIPT_HASH));
    }

    @Test
    public void notificationRecordedBeforeOlderResponseIsNotOverwritten() {
        ScriptHashSubscriptions subscriptions = new ScriptHashSubscriptions();
        subscriptions.subscribe(SCRIPT_HASH);

        //the backend reader thread records a notification read after the response, before the request thread records the response
        assertTrue(subscriptions.recordNotification(SCRIPT_HASH, STATUS_2, 6));
        subscriptions.recordSubscribeResponse(SCRIPT_HASH, STATUS_1, 5);

        assertEquals(STATUS_2, subscriptions.getStatus(SCRIPT_HASH));
    }

    @Test
    public void resubscribeResponseReplacesOlderNotification() {
        ScriptHashSubscriptions subscriptions = new ScriptHashSubscriptions();
        subscriptions.subscribe(SCRIPT_HASH);
        subscriptions.recordSubscribeResponse(SCRIPT_HASH, STATUS_1, 1);
        assertTrue(subscriptions.recordNotification(SCRIPT_HASH, STATUS_1, 2));

        //the client subscribes again, and the backend responds with a newer status than the last notification
        subscriptions.subscribe(SCRIPT_HASH);
        assertFalse(subscriptions.isPending(SCRIPT_HASH));
        subscriptions.recordSubscribeResponse(SCRIPT_HASH, STATUS_2, 3);

        assertEquals(STATUS_2, subscriptions.getStatus(SCRIPT_HASH));
        assertEquals(1, subscriptions.size());
    }

    @Test
    public void notificationForUnsubscribedScriptHashIsNotRecorded() {
        ScriptHashSubscriptions subscriptions = new ScriptHashSubscriptions();
        subscriptions.subscribe(SCRIPT_HASH);
        subscriptions.recordSubscribeResponse(SCRIPT_HASH, STATUS_1, 1);
        assertTrue(subscriptions.unsubscribe(SCRIPT_HASH));

        assertFalse(subscriptions.recordNotification(SCRIPT_HASH, STATUS_2, 2));
        subscriptions.recordSubscribeResponse(SCRIPT_HASH, STATUS_2, 3);

        assertFalse(subscriptions.isSubscribed(SCRIPT_HASH));
        assertEquals(0, subscriptions.size());
    }

    @Test
    public void subscribeReportsWhetherAdded() {
        ScriptHashSubscriptions subscriptions = new ScriptHashSubscriptions();
        assertTrue(subscriptions.subscribe(SCRIPT_HASH));
        assertFalse(subscriptions.subscribe(SCRIPT_HASH));

        subscriptions.recordSubscribeResponse(SCRIPT_HASH, STATUS_1, 1);
        assertFalse(subscriptions.subscribe(SCRIPT_HASH));
        assertEquals(STATUS_1, subscriptions.getStatus(SCRIPT_HASH));

        subscriptions.unsubscribe(SCRIPT_HASH);
        assertTrue(subscriptions.subscribe(SCRIPT_HASH));
    }

    @Test
    public void unsubscribeUnknownScriptHash() {
        ScriptHashSubscriptions subscriptions = new ScriptHashSubscriptions();
        assertFalse(subscriptions.unsubscribe(SCRIPT_HASH));
    }
}
