package com.sparrowwallet.frigate.electrum;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class AsyncNotifierTest {
    private static final String SCRIPT_HASH_A = "a0".repeat(32);
    private static final String SCRIPT_HASH_B = "b0".repeat(32);
    private static final String SCRIPT_HASH_C = "c0".repeat(32);
    private static final String SCRIPT_HASH_D = "d0".repeat(32);
    private static final String SCRIPT_HASH_E = "e0".repeat(32);
    private static final String STATUS_1 = "01".repeat(32);
    private static final String STATUS_2 = "02".repeat(32);
    private static final String STATUS_3 = "03".repeat(32);

    private final List<String> delivered = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch writerBlocked = new CountDownLatch(1);
    private final CountDownLatch releaseWriter = new CountDownLatch(1);
    private final AtomicInteger overflows = new AtomicInteger();
    private volatile boolean blockFirstWrite;
    private final ScriptHashSubscriptions subscriptions = new ScriptHashSubscriptions();
    private AsyncNotifier notifier;

    @BeforeEach
    public void setUp() {
        subscriptions.subscribe(SCRIPT_HASH_A);
        subscriptions.subscribe(SCRIPT_HASH_B);
    }

    @AfterEach
    public void tearDown() {
        releaseWriter.countDown();
        if(notifier != null) {
            notifier.close();
        }
    }

    private AsyncNotifier createNotifier(int queueSize) {
        notifier = new AsyncNotifier("test-notifier", queueSize, subscriptions, (scriptHash, status) -> write(scriptHash + ":" + status), overflows::incrementAndGet);
        return notifier;
    }

    private long sequence;

    private void notifyStatus(String scriptHash, String status) {
        notifier.notifyScriptHash(scriptHash, status, ++sequence);
    }

    private void write(String entry) {
        if(blockFirstWrite) {
            blockFirstWrite = false;
            writerBlocked.countDown();
            try {
                releaseWriter.await();
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        delivered.add(entry);
    }

    /** Starts the notifier with its drain thread parked inside a write, so everything notified afterwards stays pending. */
    private void startWithStalledClient() throws InterruptedException {
        blockFirstWrite = true;
        notifier.start();
        notifier.notify(() -> write("stall"));
        assertTrue(writerBlocked.await(5, TimeUnit.SECONDS));
    }

    private void awaitDelivered(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while(delivered.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(count, delivered.size(), "delivered: " + delivered);
    }

    @Test
    public void deliversOnNotifierThread() throws InterruptedException {
        createNotifier(10);
        notifier.start();
        List<String> threadNames = Collections.synchronizedList(new ArrayList<>());

        notifier.notify(() -> {
            threadNames.add(Thread.currentThread().getName());
            delivered.add("header");
        });
        notifyStatus(SCRIPT_HASH_A, STATUS_1);

        awaitDelivered(2);
        assertEquals(List.of("header", SCRIPT_HASH_A + ":" + STATUS_1), delivered);
        assertEquals(List.of("test-notifier"), threadNames);
    }

    @Test
    public void coalescesStatusesOfSameScriptHash() throws InterruptedException {
        createNotifier(10);
        startWithStalledClient();

        notifyStatus(SCRIPT_HASH_A, STATUS_1);
        notifyStatus(SCRIPT_HASH_B, STATUS_1);
        notifyStatus(SCRIPT_HASH_A, STATUS_2);
        notifyStatus(SCRIPT_HASH_A, STATUS_3);
        assertEquals(2, notifier.getPendingCount());

        releaseWriter.countDown();

        awaitDelivered(3);
        //A keeps its original queue position but delivers only its newest status
        assertEquals(List.of("stall", SCRIPT_HASH_A + ":" + STATUS_3, SCRIPT_HASH_B + ":" + STATUS_1), delivered);
    }

    @Test
    public void nullStatusIsCoalescedAndDelivered() throws InterruptedException {
        createNotifier(10);
        startWithStalledClient();

        notifyStatus(SCRIPT_HASH_A, null);
        notifyStatus(SCRIPT_HASH_A, null);
        notifyStatus(SCRIPT_HASH_B, STATUS_1);
        notifyStatus(SCRIPT_HASH_B, null);
        assertEquals(2, notifier.getPendingCount());

        releaseWriter.countDown();

        awaitDelivered(3);
        assertEquals(Arrays.asList("stall", SCRIPT_HASH_A + ":null", SCRIPT_HASH_B + ":null"), delivered);
    }

    @Test
    public void otherNotificationsPrecedePendingStatuses() throws InterruptedException {
        createNotifier(10);
        startWithStalledClient();

        notifyStatus(SCRIPT_HASH_A, STATUS_1);
        notifier.notify(() -> delivered.add("header"));
        releaseWriter.countDown();

        awaitDelivered(3);
        assertEquals(List.of("stall", "header", SCRIPT_HASH_A + ":" + STATUS_1), delivered);
    }

    @Test
    public void fullQueueOverflowsOnce() throws InterruptedException {
        createNotifier(2);
        startWithStalledClient();

        notifier.notify(() -> delivered.add("1"));
        notifier.notify(() -> delivered.add("2"));
        assertEquals(0, overflows.get());
        notifier.notify(() -> delivered.add("3"));
        notifier.notify(() -> delivered.add("4"));
        notifyStatus(SCRIPT_HASH_A, STATUS_1);

        assertEquals(1, overflows.get());
        assertTrue(notifier.isClosed());
        releaseWriter.countDown();
        assertTrue(notifier.awaitTermination(5000));
        assertEquals(List.of("stall"), delivered);
    }

    @Test
    public void pendingStatusesBeyondSubscriptionCountOverflow() throws InterruptedException {
        subscriptions.unsubscribe(SCRIPT_HASH_B);
        createNotifier(1);
        startWithStalledClient();

        notifyStatus(SCRIPT_HASH_A, STATUS_1);
        notifyStatus(SCRIPT_HASH_B, STATUS_1);
        assertEquals(0, overflows.get());
        notifyStatus(SCRIPT_HASH_C, STATUS_1);

        assertEquals(1, overflows.get());
        assertTrue(notifier.isClosed());
    }

    @Test
    public void closeWakesIdleDrainThread() throws InterruptedException {
        createNotifier(10);
        notifier.start();

        notifier.close();

        assertTrue(notifier.awaitTermination(5000));
        notifyStatus(SCRIPT_HASH_A, STATUS_1);
        notifier.notify(() -> delivered.add("header"));
        assertEquals(0, notifier.getPendingCount());
        assertEquals(0, overflows.get());
    }

    @Test
    public void closeDiscardsUndelivered() throws InterruptedException {
        createNotifier(10);
        startWithStalledClient();
        notifyStatus(SCRIPT_HASH_A, STATUS_1);
        notifier.notify(() -> delivered.add("header"));

        notifier.close();
        releaseWriter.countDown();

        assertTrue(notifier.awaitTermination(5000));
        assertEquals(List.of("stall"), delivered);
    }

    @Test
    public void discardedStatusIsNotDelivered() throws InterruptedException {
        createNotifier(10);
        startWithStalledClient();

        notifyStatus(SCRIPT_HASH_A, STATUS_1);
        notifyStatus(SCRIPT_HASH_B, STATUS_1);
        notifier.discardScriptHash(SCRIPT_HASH_A);
        assertEquals(1, notifier.getPendingCount());

        releaseWriter.countDown();

        awaitDelivered(2);
        assertEquals(List.of("stall", SCRIPT_HASH_B + ":" + STATUS_1), delivered);
    }

    @Test
    public void statusAfterDiscardIsDeliveredOnce() throws InterruptedException {
        createNotifier(10);
        startWithStalledClient();

        notifyStatus(SCRIPT_HASH_A, STATUS_1);
        notifier.discardScriptHash(SCRIPT_HASH_A);
        notifyStatus(SCRIPT_HASH_B, STATUS_1);
        notifyStatus(SCRIPT_HASH_A, STATUS_2);
        releaseWriter.countDown();

        awaitDelivered(3);
        //a status notified after a discard takes a new place at the end of the delivery order
        assertEquals(List.of("stall", SCRIPT_HASH_B + ":" + STATUS_1, SCRIPT_HASH_A + ":" + STATUS_2), delivered);
        notifier.notify(() -> delivered.add("marker"));
        awaitDelivered(4);
        assertEquals("marker", delivered.getLast());
    }

    @Test
    public void discardedStatusesDoNotCountTowardsOverflow() throws InterruptedException {
        subscriptions.subscribe(SCRIPT_HASH_C);
        subscriptions.subscribe(SCRIPT_HASH_D);
        createNotifier(1);
        startWithStalledClient();

        List<String> scriptHashes = List.of(SCRIPT_HASH_A, SCRIPT_HASH_B, SCRIPT_HASH_C, SCRIPT_HASH_D);
        for(String scriptHash : scriptHashes) {
            notifyStatus(scriptHash, STATUS_1);
        }

        //the client unsubscribes everything and subscribes one new scripthash
        for(String scriptHash : scriptHashes) {
            subscriptions.unsubscribe(scriptHash);
            notifier.discardScriptHash(scriptHash);
        }
        subscriptions.subscribe(SCRIPT_HASH_E);
        notifyStatus(SCRIPT_HASH_E, STATUS_1);

        assertEquals(0, overflows.get());
        assertEquals(1, notifier.getPendingCount());
    }

    @Test
    public void statusQueuedAfterUnsubscribeIsSkipped() throws InterruptedException {
        createNotifier(10);
        startWithStalledClient();

        //the unsubscribe and its discard run between the backend notification being recorded and it being queued
        subscriptions.unsubscribe(SCRIPT_HASH_A);
        notifier.discardScriptHash(SCRIPT_HASH_A);
        notifyStatus(SCRIPT_HASH_A, STATUS_1);
        notifyStatus(SCRIPT_HASH_B, STATUS_1);
        releaseWriter.countDown();

        awaitDelivered(2);
        assertEquals(List.of("stall", SCRIPT_HASH_B + ":" + STATUS_1), delivered);
        notifier.notify(() -> delivered.add("marker"));
        awaitDelivered(3);
        assertEquals(List.of("stall", SCRIPT_HASH_B + ":" + STATUS_1, "marker"), delivered);
    }

    @Test
    public void subscribeResponseDiscardsOnlyOlderPendingStatus() throws InterruptedException {
        createNotifier(10);
        startWithStalledClient();

        notifier.notifyScriptHash(SCRIPT_HASH_A, STATUS_1, 10);
        notifier.notifyScriptHash(SCRIPT_HASH_B, STATUS_1, 12);
        //a subscribe response read at sequence 11 supersedes A's pending status but not B's
        notifier.discardScriptHash(SCRIPT_HASH_A, 11);
        notifier.discardScriptHash(SCRIPT_HASH_B, 11);
        releaseWriter.countDown();

        awaitDelivered(2);
        assertEquals(List.of("stall", SCRIPT_HASH_B + ":" + STATUS_1), delivered);
    }

    @Test
    public void heldStatusIsDeliveredAfterRelease() throws InterruptedException {
        createNotifier(10);
        notifier.start();

        //a subscribe for A is in progress: its newer status must not reach the client before the subscribe response
        notifier.hold(SCRIPT_HASH_A);
        notifyStatus(SCRIPT_HASH_A, STATUS_2);
        notifyStatus(SCRIPT_HASH_B, STATUS_1);
        awaitDelivered(1);
        assertEquals(List.of(SCRIPT_HASH_B + ":" + STATUS_1), delivered);
        assertEquals(1, notifier.getPendingCount());

        //the request thread writes the subscribe response, then releases
        delivered.add("response");
        notifier.release(SCRIPT_HASH_A);

        awaitDelivered(3);
        assertEquals(List.of(SCRIPT_HASH_B + ":" + STATUS_1, "response", SCRIPT_HASH_A + ":" + STATUS_2), delivered);
    }

    @Test
    public void holdsAreCounted() throws InterruptedException {
        createNotifier(10);
        notifier.start();

        //the request thread and the backend session both hold A
        notifier.hold(SCRIPT_HASH_A);
        notifier.hold(SCRIPT_HASH_A);
        notifyStatus(SCRIPT_HASH_A, STATUS_1);
        notifier.release(SCRIPT_HASH_A);
        notifyStatus(SCRIPT_HASH_B, STATUS_1);
        awaitDelivered(1);
        assertEquals(List.of(SCRIPT_HASH_B + ":" + STATUS_1), delivered);

        notifier.release(SCRIPT_HASH_A);
        awaitDelivered(2);
        assertEquals(List.of(SCRIPT_HASH_B + ":" + STATUS_1, SCRIPT_HASH_A + ":" + STATUS_1), delivered);

        //releasing a scripthash that is not held has no effect
        notifier.release(SCRIPT_HASH_A);
        notifyStatus(SCRIPT_HASH_A, STATUS_2);
        awaitDelivered(3);
    }

    @Test
    public void olderStatusDoesNotReplaceNewerPending() throws InterruptedException {
        createNotifier(10);
        startWithStalledClient();

        //a notification from the new connection (sequence 20) is queued before the catch-up status (sequence 19) of its resubscribe
        notifier.notifyScriptHash(SCRIPT_HASH_A, STATUS_2, 20);
        notifier.notifyScriptHash(SCRIPT_HASH_A, STATUS_1, 19);
        releaseWriter.countDown();

        awaitDelivered(2);
        assertEquals(List.of("stall", SCRIPT_HASH_A + ":" + STATUS_2), delivered);
    }

    @Test
    public void closeWakesDrainThreadWaitingOnHeldStatus() throws InterruptedException {
        createNotifier(10);
        notifier.start();
        notifier.hold(SCRIPT_HASH_A);
        notifyStatus(SCRIPT_HASH_A, STATUS_1);

        notifier.close();

        assertTrue(notifier.awaitTermination(5000));
        assertTrue(delivered.isEmpty());
    }

    @Test
    public void isDeliveringWhileWriteBlocked() throws InterruptedException {
        createNotifier(10);
        assertFalse(notifier.isDelivering());
        startWithStalledClient();

        notifier.close();
        assertTrue(notifier.isDelivering());

        releaseWriter.countDown();
        assertTrue(notifier.awaitTermination(5000));
        assertFalse(notifier.isDelivering());
    }

    @Test
    public void failingWriteDoesNotStopDelivery() throws InterruptedException {
        createNotifier(10);
        notifier.start();

        notifier.notify(() -> {
            throw new IllegalStateException("write failed");
        });
        notifyStatus(SCRIPT_HASH_A, STATUS_1);

        awaitDelivered(1);
        assertEquals(List.of(SCRIPT_HASH_A + ":" + STATUS_1), delivered);
    }
}
