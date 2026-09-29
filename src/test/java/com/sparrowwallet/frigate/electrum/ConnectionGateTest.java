package com.sparrowwallet.frigate.electrum;

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

        assertEquals(ConnectionGate.IpKey.of(address("10.0.0.1")), ConnectionGate.IpKey.of(mappedAddress));
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
                        if(ipHeld.incrementAndGet() > maxConnectionsPerIp && !key.loopback()) {
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
