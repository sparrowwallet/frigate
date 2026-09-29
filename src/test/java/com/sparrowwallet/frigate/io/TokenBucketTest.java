package com.sparrowwallet.frigate.io;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

public class TokenBucketTest {
    /** A simulated clock: sleeping advances it by the time slept, and records each sleep. */
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final List<Long> sleeps = new ArrayList<>();

    private TokenBucket bucket(long capacity, long refillPerSecond) {
        return new TokenBucket(capacity, refillPerSecond, nanos::get, millis -> {
            sleeps.add(millis);
            nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
        });
    }

    private void advance(long millis) {
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
    }

    @Test
    public void startsFull() throws Exception {
        TokenBucket bucket = bucket(100, 10);

        bucket.acquire(100);

        assertTrue(sleeps.isEmpty());
        assertEquals(0, bucket.getTokens(), 1e-9);
    }

    @Test
    public void waitsForRefillWhenEmpty() throws Exception {
        TokenBucket bucket = bucket(10, 10);
        bucket.acquire(10);

        //5 tokens at 10 per second take 500ms to refill
        bucket.acquire(5);

        assertEquals(List.of(500L), sleeps);
        assertEquals(0, bucket.getTokens(), 1e-9);
    }

    @Test
    public void waitsOnlyForShortfall() throws Exception {
        TokenBucket bucket = bucket(10, 100);
        bucket.acquire(8);

        //2 remain, so 5 needs 3 more: 30ms at 100 per second
        bucket.acquire(5);

        assertEquals(List.of(30L), sleeps);
    }

    @Test
    public void refillIsCappedAtCapacity() throws Exception {
        TokenBucket bucket = bucket(10, 100);
        bucket.acquire(10);

        advance(60_000);

        assertEquals(10, bucket.getTokens(), 1e-9);
        bucket.acquire(10);
        assertTrue(sleeps.isEmpty());
        assertEquals(0, bucket.getTokens(), 1e-9);
    }

    @Test
    public void costAboveCapacityWaitsForFullBucketThenGoesIntoDebt() throws Exception {
        TokenBucket bucket = bucket(10, 10);
        bucket.acquire(4);

        //waits for a full bucket (the 4 used to refill), rather than forever, then takes the whole cost
        bucket.acquire(25);
        assertEquals(List.of(400L), sleeps);
        assertEquals(-15, bucket.getTokens(), 1e-9);

        //the next request waits for the debt to be repaid: 16 tokens at 10 per second
        bucket.acquire(1);
        assertEquals(List.of(400L, 1600L), sleeps);
    }

    @Test
    public void sustainedRateOfExpensiveRequestsMatchesTheirCost() throws Exception {
        TokenBucket bucket = bucket(1000, 100);
        long start = nanos.get();

        //11 batches costing 2,500 each, 2.5 times the capacity: the first leaves the full bucket at -1,500, and each later one waits
        //for it to refill to full (2,500 tokens, 25 seconds at 100 per second) before leaving it at -1,500 again
        for(int i = 0; i < 11; i++) {
            bucket.acquire(2500);
        }

        //10 waits of 25 seconds: 25,000 tokens repaid for 25,000 spent after the first, the rate the cost implies
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(nanos.get() - start);
        assertTrue(elapsedMillis >= 249_900 && elapsedMillis <= 250_100, "elapsed " + elapsedMillis + "ms");
    }

    @Test
    public void zeroCostDoesNotWaitOrTake() throws Exception {
        TokenBucket bucket = bucket(10, 10);
        bucket.acquire(10);

        bucket.acquire(0);

        assertTrue(sleeps.isEmpty());
    }

    @Test
    public void sustainedRateMatchesRefill() throws Exception {
        TokenBucket bucket = bucket(100, 100);
        long start = nanos.get();

        //1,100 requests: the first 100 from the full bucket, the next 1,000 at 100 per second
        for(int i = 0; i < 1100; i++) {
            bucket.acquire(1);
        }

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(nanos.get() - start);
        assertTrue(elapsedMillis >= 9_990 && elapsedMillis <= 10_010, "elapsed " + elapsedMillis + "ms");
    }

    @Test
    public void waitingDoesNotHoldTheMonitor() throws Exception {
        CountDownLatch sleeping = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TokenBucket bucket = new TokenBucket(1, 1, System::nanoTime, millis -> {
            sleeping.countDown();
            release.await();
        });
        bucket.acquire(1);

        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                bucket.acquire(1);
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(sleeping.await(5, TimeUnit.SECONDS));

        //another user of the bucket is not blocked by the waiting thread
        Thread reader = Thread.ofVirtual().start(bucket::getTokens);
        assertTrue(reader.join(Duration.ofSeconds(5)));

        release.countDown();
        waiter.interrupt();
        assertTrue(waiter.join(Duration.ofSeconds(5)));
    }
}
