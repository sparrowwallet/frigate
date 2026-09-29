package com.sparrowwallet.frigate.electrum;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Admits client connections within a global cap and a per-IP cap, consulted in the accept loop before a session is created.
 * Also accounts for scripthash subscriptions within a global cap and a per-IP cap, reserved by sessions as clients subscribe.
 *
 * Clients are grouped by IPv4 address, or by IPv6 /64 prefix, as a single IPv6 allocation is usually a /64 and a per-address
 * limit would be trivially evaded within it. IPv4-mapped IPv6 addresses are grouped as their IPv4 address. Loopback connections
 * (operator tooling, the CLI, benchmarks) are exempt from the per-IP caps, but not from the global caps. This also means that
 * behind a reverse proxy on the same host, where every client arrives from loopback, only the global caps apply.
 */
public class ConnectionGate {
    private final int maxConnections;
    private final int maxConnectionsPerIp;
    private final long maxSubscriptions;
    private final int maxSubscriptionsPerIp;
    private final AtomicInteger total = new AtomicInteger();
    private final ConcurrentHashMap<IpKey, Integer> perIp = new ConcurrentHashMap<>();
    private final AtomicLong subscriptions = new AtomicLong();
    private final ConcurrentHashMap<IpKey, Integer> subscriptionsPerIp = new ConcurrentHashMap<>();

    /**
     * A gate limiting connections only, with no subscription limits.
     */
    public ConnectionGate(int maxConnections, int maxConnectionsPerIp) {
        this(maxConnections, maxConnectionsPerIp, Long.MAX_VALUE, Integer.MAX_VALUE);
    }

    public ConnectionGate(int maxConnections, int maxConnectionsPerIp, long maxSubscriptions, int maxSubscriptionsPerIp) {
        this.maxConnections = maxConnections;
        this.maxConnectionsPerIp = maxConnectionsPerIp;
        this.maxSubscriptions = maxSubscriptions;
        this.maxSubscriptionsPerIp = maxSubscriptionsPerIp;
    }

    /**
     * @return the client's IP key, to be passed to release() when the session closes, or null if the connection is refused
     */
    public IpKey tryAcquire(InetAddress address) {
        if(total.incrementAndGet() > maxConnections) {
            total.decrementAndGet();
            return null;
        }

        IpKey key = IpKey.of(address);
        //the check and increment happen inside one compute(): a separate check then increment would race release()'s
        //remove-on-zero, orphaning the incremented count and leaking a slot each time
        boolean[] admitted = new boolean[1];
        perIp.compute(key, (k, count) -> {
            int current = count == null ? 0 : count;
            if(!key.loopback() && current >= maxConnectionsPerIp) {
                admitted[0] = false;
                return count;
            }
            admitted[0] = true;
            return current + 1;
        });

        if(!admitted[0]) {
            total.decrementAndGet();
            return null;
        }
        return key;
    }

    public void release(IpKey key) {
        perIp.compute(key, (k, count) -> count == null || count <= 1 ? null : count - 1);
        total.decrementAndGet();
    }

    /**
     * Reserves one scripthash subscription for a client, within the global and per-IP subscription caps.
     * @return true if reserved, to be given back with releaseSubscriptions() when the subscription ends
     */
    public boolean tryReserveSubscription(IpKey key) {
        if(subscriptions.incrementAndGet() > maxSubscriptions) {
            subscriptions.decrementAndGet();
            return false;
        }

        boolean[] reserved = new boolean[1];
        subscriptionsPerIp.compute(key, (k, count) -> {
            int current = count == null ? 0 : count;
            if(!key.loopback() && current >= maxSubscriptionsPerIp) {
                reserved[0] = false;
                return count;
            }
            reserved[0] = true;
            return current + 1;
        });

        if(!reserved[0]) {
            subscriptions.decrementAndGet();
        }
        return reserved[0];
    }

    public void releaseSubscriptions(IpKey key, int count) {
        if(count <= 0) {
            return;
        }
        subscriptionsPerIp.compute(key, (k, current) -> current == null || current <= count ? null : current - count);
        subscriptions.addAndGet(-count);
    }

    public long getSubscriptionCount() {
        return subscriptions.get();
    }

    public int getSubscriptionCount(IpKey key) {
        return subscriptionsPerIp.getOrDefault(key, 0);
    }

    public int getConnectionCount() {
        return total.get();
    }

    public int getConnectionCount(IpKey key) {
        return perIp.getOrDefault(key, 0);
    }

    public int getDistinctIpCount() {
        return perIp.size();
    }

    /**
     * Identifies a client for per-IP limits: an IPv4 address, or an IPv6 /64 prefix.
     */
    public record IpKey(String value, boolean loopback) {
        public static IpKey of(InetAddress address) {
            byte[] bytes = address.getAddress();
            if(address instanceof Inet6Address && isIpv4Mapped(bytes)) {
                try {
                    return of(Inet4Address.getByAddress(new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]}));
                } catch(UnknownHostException e) {
                    throw new IllegalStateException(e);
                }
            }
            if(address instanceof Inet6Address && !address.isLoopbackAddress()) {
                String prefix = HexFormat.of().formatHex(bytes, 0, 8);
                return new IpKey(prefix.replaceAll("(.{4})(?!$)", "$1:") + "::/64", false);
            }
            return new IpKey(address.getHostAddress(), address.isLoopbackAddress());
        }

        private static boolean isIpv4Mapped(byte[] bytes) {
            if(bytes.length != 16) {
                return false;
            }
            for(int i = 0; i < 10; i++) {
                if(bytes[i] != 0) {
                    return false;
                }
            }
            return bytes[10] == (byte)0xff && bytes[11] == (byte)0xff;
        }

        @Override
        public String toString() {
            return value;
        }
    }
}
