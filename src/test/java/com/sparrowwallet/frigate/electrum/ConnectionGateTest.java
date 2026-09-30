package com.sparrowwallet.frigate.electrum;

import com.sparrowwallet.frigate.io.Subnet;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class ConnectionGateTest {
    private static final List<Subnet> LOOPBACK = List.of(Subnet.parse("127.0.0.1/32"), Subnet.parse("::1/128"));

    private static InetAddress address(String host) throws Exception {
        return InetAddress.getByName(host);
    }

    @Test
    public void globalCapIsEnforced() throws Exception {
        ConnectionGate gate = new ConnectionGate(2, 10);

        ConnectionGate.IpKey first = gate.tryAcquire(address("10.0.0.1"));
        ConnectionGate.IpKey second = gate.tryAcquire(address("10.0.0.2"));
        assertNotNull(first);
        assertNotNull(second);
        assertNull(gate.tryAcquire(address("10.0.0.3")));
        assertEquals(2, gate.getConnectionCount());

        gate.release(first);
        assertNotNull(gate.tryAcquire(address("10.0.0.3")));
    }

    @Test
    public void perIpCapIsEnforced() throws Exception {
        ConnectionGate gate = new ConnectionGate(100, 2);

        ConnectionGate.IpKey first = gate.tryAcquire(address("10.0.0.1"));
        assertNotNull(gate.tryAcquire(address("10.0.0.1")));
        assertNull(gate.tryAcquire(address("10.0.0.1")));
        //a refused connection does not use a global slot
        assertEquals(2, gate.getConnectionCount());
        assertEquals(2, gate.getConnectionCount(first));
        //other addresses are unaffected
        assertNotNull(gate.tryAcquire(address("10.0.0.2")));

        gate.release(first);
        assertNotNull(gate.tryAcquire(address("10.0.0.1")));
    }

    @Test
    public void releaseRemovesIdleAddresses() throws Exception {
        ConnectionGate gate = new ConnectionGate(100, 5);
        ConnectionGate.IpKey key = gate.tryAcquire(address("10.0.0.1"));
        gate.tryAcquire(address("10.0.0.1"));
        assertEquals(1, gate.getDistinctIpCount());

        gate.release(key);
        gate.release(key);

        assertEquals(0, gate.getDistinctIpCount());
        assertEquals(0, gate.getConnectionCount());
        assertEquals(0, gate.getConnectionCount(key));
    }

    @Test
    public void loopbackIsExemptFromPerIpCapButNotGlobalCap() throws Exception {
        ConnectionGate gate = new ConnectionGate(5, 1);

        for(int i = 0; i < 3; i++) {
            assertNotNull(gate.tryAcquire(InetAddress.getLoopbackAddress()));
        }
        assertNotNull(gate.tryAcquire(address("::1")));
        assertNotNull(gate.tryAcquire(address("10.0.0.1")));
        assertNull(gate.tryAcquire(InetAddress.getLoopbackAddress()));
    }

    @Test
    public void ipv6IsGroupedBySlash64() throws Exception {
        ConnectionGate gate = new ConnectionGate(100, 2);

        ConnectionGate.IpKey key = gate.tryAcquire(address("2001:db8:1:2::1"));
        assertEquals("2001:0db8:0001:0002::/64", key.value());
        assertNotNull(gate.tryAcquire(address("2001:db8:1:2:ffff:ffff:ffff:ffff")));
        //a third address in the same /64 is refused
        assertNull(gate.tryAcquire(address("2001:db8:1:2:abcd::9")));
        //a neighbouring /64 is a different client
        assertNotNull(gate.tryAcquire(address("2001:db8:1:3::1")));
    }

    @Test
    public void ipv4MappedAddressIsGroupedAsIpv4() throws Exception {
        byte[] mapped = new byte[16];
        mapped[10] = (byte)0xff;
        mapped[11] = (byte)0xff;
        mapped[12] = 10;
        mapped[15] = 1;
        java.net.Inet6Address mappedAddress = java.net.Inet6Address.getByAddress(null, mapped, -1);

        ConnectionGate gate = new ConnectionGate(100, 100);
        assertEquals(gate.keyFor(address("10.0.0.1")), gate.keyFor(mappedAddress));
    }

    @Test
    public void globalSubscriptionCapIsEnforced() throws Exception {
        ConnectionGate gate = new ConnectionGate(100, 100, 3, 100, LOOPBACK);
        ConnectionGate.IpKey first = gate.keyFor(address("10.0.0.1"));
        ConnectionGate.IpKey second = gate.keyFor(address("10.0.0.2"));

        assertTrue(gate.tryReserveSubscription(first));
        assertTrue(gate.tryReserveSubscription(first));
        assertTrue(gate.tryReserveSubscription(second));
        assertFalse(gate.tryReserveSubscription(second));
        assertEquals(3, gate.getSubscriptionCount());

        gate.releaseSubscriptions(first, 1);
        assertTrue(gate.tryReserveSubscription(second));
    }

    @Test
    public void perIpSubscriptionCapIsEnforced() throws Exception {
        ConnectionGate gate = new ConnectionGate(100, 100, 1000, 2, LOOPBACK);
        ConnectionGate.IpKey first = gate.keyFor(address("10.0.0.1"));
        ConnectionGate.IpKey second = gate.keyFor(address("10.0.0.2"));

        assertTrue(gate.tryReserveSubscription(first));
        assertTrue(gate.tryReserveSubscription(first));
        assertFalse(gate.tryReserveSubscription(first));
        //a refused reservation does not use a global slot
        assertEquals(2, gate.getSubscriptionCount());
        assertTrue(gate.tryReserveSubscription(second));

        gate.releaseSubscriptions(first, 2);
        assertEquals(0, gate.getSubscriptionCount(first));
        assertEquals(1, gate.getSubscriptionCount());
        assertTrue(gate.tryReserveSubscription(first));
    }

    @Test
    public void loopbackIsExemptFromPerIpSubscriptionCapButNotGlobalCap() throws Exception {
        ConnectionGate gate = new ConnectionGate(100, 100, 4, 1, LOOPBACK);
        ConnectionGate.IpKey loopback = gate.keyFor(InetAddress.getLoopbackAddress());

        for(int i = 0; i < 4; i++) {
            assertTrue(gate.tryReserveSubscription(loopback));
        }
        assertFalse(gate.tryReserveSubscription(loopback));
    }

    @Test
    public void releasingNothingIsIgnored() throws Exception {
        ConnectionGate gate = new ConnectionGate(100, 100, 10, 10, LOOPBACK);
        ConnectionGate.IpKey key = gate.keyFor(address("10.0.0.1"));

        gate.releaseSubscriptions(key, 0);

        assertEquals(0, gate.getSubscriptionCount());
        assertEquals(0, gate.getSubscriptionCount(key));
    }

    @Test
    public void excludedSubnetsAreExemptFromPerIpCaps() throws Exception {
        ConnectionGate gate = new ConnectionGate(100, 1, 100, 1, List.of(Subnet.parse("192.168.0.0/16")));

        //a client on the excluded network is exempt from the per-IP connection and subscription caps
        assertNotNull(gate.tryAcquire(address("192.168.1.20")));
        assertNotNull(gate.tryAcquire(address("192.168.1.20")));
        assertTrue(gate.tryReserveSubscription(gate.keyFor(address("192.168.1.20"))));
        assertTrue(gate.tryReserveSubscription(gate.keyFor(address("192.168.1.20"))));
        assertTrue(gate.keyFor(address("192.168.1.20")).exempt());

        //others are not, and loopback is not exempt unless it is listed
        for(String host : List.of("10.0.0.1", "127.0.0.1")) {
            assertFalse(gate.keyFor(address(host)).exempt());
            assertNotNull(gate.tryAcquire(address(host)));
            assertNull(gate.tryAcquire(address(host)));
        }
    }

    @Test
    public void noExcludedSubnetsLimitsLoopback() throws Exception {
        ConnectionGate gate = new ConnectionGate(100, 1, 100, 1, List.of());

        assertNotNull(gate.tryAcquire(InetAddress.getLoopbackAddress()));
        assertNull(gate.tryAcquire(InetAddress.getLoopbackAddress()));
    }

    @Test
    public void concurrentSubscriptionReservationsRespectCaps() throws Exception {
        int maxSubscriptions = 10;
        int maxSubscriptionsPerIp = 3;
        ConnectionGate gate = new ConnectionGate(100, 100, maxSubscriptions, maxSubscriptionsPerIp, LOOPBACK);
        List<ConnectionGate.IpKey> keys = List.of(gate.keyFor(address("10.0.0.1")), gate.keyFor(address("10.0.0.2")),
                gate.keyFor(address("10.0.0.3")), gate.keyFor(address("2001:db8::1")), gate.keyFor(InetAddress.getLoopbackAddress()));

        //reservations actually held, counted by the test, as the gate's global count can briefly exceed its cap while a refusal backs out
        AtomicInteger held = new AtomicInteger();
        ConcurrentHashMap<ConnectionGate.IpKey, AtomicInteger> heldPerIp = new ConcurrentHashMap<>();
        AtomicInteger maxHeld = new AtomicInteger();
        AtomicInteger overPerIpCap = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();

        for(int t = 0; t < 32; t++) {
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    for(int i = 0; i < 20_000; i++) {
                        ConnectionGate.IpKey key = keys.get(ThreadLocalRandom.current().nextInt(keys.size()));
                        if(!gate.tryReserveSubscription(key)) {
                            refused.incrementAndGet();
                            continue;
                        }
                        maxHeld.accumulateAndGet(held.incrementAndGet(), Math::max);
                        AtomicInteger ipHeld = heldPerIp.computeIfAbsent(key, k -> new AtomicInteger());
                        if(ipHeld.incrementAndGet() > maxSubscriptionsPerIp && !key.exempt()) {
                            overPerIpCap.incrementAndGet();
                        }
                        Thread.yield();
                        ipHeld.decrementAndGet();
                        held.decrementAndGet();
                        gate.releaseSubscriptions(key, 1);
                    }
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for(Thread thread : threads) {
            thread.join();
        }

        assertTrue(maxHeld.get() <= maxSubscriptions, "held " + maxHeld.get() + " reservations at once");
        assertEquals(0, overPerIpCap.get());
        assertTrue(refused.get() > 0);
        assertEquals(0, gate.getSubscriptionCount());
        for(ConnectionGate.IpKey key : keys) {
            assertEquals(0, gate.getSubscriptionCount(key));
        }
    }

    @Test
    public void concurrentAcquireAndReleaseRespectCapsAndLeaveNoCounts() throws Exception {
        //more threads than the global cap, so it is contended; loopback is uncapped per IP, so the global cap binds too
        int maxConnections = 10;
        int maxConnectionsPerIp = 3;
        ConnectionGate gate = new ConnectionGate(maxConnections, maxConnectionsPerIp);
        List<InetAddress> addresses = List.of(address("10.0.0.1"), address("10.0.0.2"), address("10.0.0.3"), address("2001:db8::1"), InetAddress.getLoopbackAddress());

        //slots actually held, counted by the test: the gate's own total can briefly exceed the cap while a refused caller backs out
        AtomicInteger held = new AtomicInteger();
        ConcurrentHashMap<ConnectionGate.IpKey, AtomicInteger> heldPerIp = new ConcurrentHashMap<>();
        AtomicInteger maxHeld = new AtomicInteger();
        AtomicInteger overPerIpCap = new AtomicInteger();
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();

        for(int t = 0; t < 32; t++) {
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    for(int i = 0; i < 20_000; i++) {
                        InetAddress address = addresses.get(ThreadLocalRandom.current().nextInt(addresses.size()));
                        ConnectionGate.IpKey key = gate.tryAcquire(address);
                        if(key == null) {
                            refused.incrementAndGet();
                            continue;
                        }
                        admitted.incrementAndGet();
                        maxHeld.accumulateAndGet(held.incrementAndGet(), Math::max);
                        AtomicInteger ipHeld = heldPerIp.computeIfAbsent(key, k -> new AtomicInteger());
                        if(ipHeld.incrementAndGet() > maxConnectionsPerIp && !key.exempt()) {
                            overPerIpCap.incrementAndGet();
                        }
                        Thread.yield();
                        ipHeld.decrementAndGet();
                        held.decrementAndGet();
                        gate.release(key);
                    }
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for(Thread thread : threads) {
            thread.join();
        }

        assertTrue(maxHeld.get() <= maxConnections, "held " + maxHeld.get() + " slots at once");
        assertEquals(0, overPerIpCap.get());
        //both outcomes occurred, so the caps were contended
        assertTrue(admitted.get() > 0 && refused.get() > 0, "admitted " + admitted.get() + ", refused " + refused.get());
        assertEquals(0, gate.getConnectionCount());
        assertEquals(0, gate.getDistinctIpCount());
    }
}
