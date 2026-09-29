package com.sparrowwallet.frigate.electrum;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Admits client connections within a global cap and a per-IP cap, consulted in the accept loop before a session is created.
 *
 * Clients are grouped by IPv4 address, or by IPv6 /64 prefix, as a single IPv6 allocation is usually a /64 and a per-address
 * limit would be trivially evaded within it. IPv4-mapped IPv6 addresses are grouped as their IPv4 address. Loopback connections
 * (operator tooling, the CLI, benchmarks) are exempt from the per-IP cap, but not from the global cap.
 */
public class ConnectionGate {
    private final int maxConnections;
    private final int maxConnectionsPerIp;
    private final AtomicInteger total = new AtomicInteger();
    private final ConcurrentHashMap<IpKey, Integer> perIp = new ConcurrentHashMap<>();

    public ConnectionGate(int maxConnections, int maxConnectionsPerIp) {
        this.maxConnections = maxConnections;
        this.maxConnectionsPerIp = maxConnectionsPerIp;
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
