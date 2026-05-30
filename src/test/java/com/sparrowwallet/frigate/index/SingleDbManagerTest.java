package com.sparrowwallet.frigate.index;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class SingleDbManagerTest {
    //regression for the three-way DB deadlock: once a writer holds the writerWaiting permit, any new executeRead parks in wait().
    //The implicit close() in try-with-resources does shutdown+awaitTermination, which doesn't interrupt - shutdownNow is the load-bearing call.
    @Test
    public void testShutdownNowInterruptsExecuteReadParkedBehindWriter() throws Exception {
        SingleDbManager dbManager = new SingleDbManager(DbManager.DB_PREFIX);
        CountDownLatch writerHoldingPermit = new CountDownLatch(1);
        CountDownLatch writerMayRelease = new CountDownLatch(1);

        //writer takes the writerWaiting permit + write lock and holds them until the test releases it
        Thread writer = new Thread(() -> {
            try {
                dbManager.executeWrite(conn -> {
                    writerHoldingPermit.countDown();
                    try {
                        writerMayRelease.await();
                    } catch(InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            } catch(Exception ignored) {
            }
        }, "deadlock-test-writer");

        try {
            writer.start();
            writerHoldingPermit.await();

            //schedule a task that will park in SingleDbManager.executeRead.wait() (writerWaiting.availablePermits()==0)
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
            executor.scheduleAtFixedRate(() -> {
                try {
                    dbManager.executeRead(conn -> null);
                } catch(Exception ignored) {
                }
            }, 0, 100, TimeUnit.MILLISECONDS);

            //give the task time to fire and park
            Thread.sleep(200);

            //the load-bearing claim of the fix: shutdownNow interrupts the parked executeRead so the executor terminates promptly
            executor.shutdownNow();
            boolean terminated = executor.awaitTermination(2, TimeUnit.SECONDS);

            assertTrue(terminated, "shutdownNow should interrupt the parked executeRead and let the executor terminate");
        } finally {
            writerMayRelease.countDown();
            writer.join(2000);
            dbManager.close();
        }
    }
}
