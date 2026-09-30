package com.sparrowwallet.frigate.io;

import com.sparrowwallet.frigate.ConfigurationException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

public class SubnetTest {
    @Test
    public void ipv4SubnetContainsOnlyItsAddresses() throws Exception {
        Subnet subnet = Subnet.parse("192.168.0.0/16");

        assertTrue(subnet.contains(InetAddress.getByName("192.168.0.1")));
        assertTrue(subnet.contains(InetAddress.getByName("192.168.255.255")));
        assertFalse(subnet.contains(InetAddress.getByName("192.169.0.1")));
        assertFalse(subnet.contains(InetAddress.getByName("::1")));
    }

    @Test
    public void prefixNotOnByteBoundary() throws Exception {
        Subnet subnet = Subnet.parse("172.16.0.0/12");

        assertTrue(subnet.contains(InetAddress.getByName("172.16.0.1")));
        assertTrue(subnet.contains(InetAddress.getByName("172.31.255.255")));
        assertFalse(subnet.contains(InetAddress.getByName("172.32.0.1")));
        assertFalse(subnet.contains(InetAddress.getByName("172.15.255.255")));
    }

    @Test
    public void ipv6Subnet() throws Exception {
        Subnet subnet = Subnet.parse("fd00::/8");

        assertTrue(subnet.contains(InetAddress.getByName("fd12:3456::1")));
        assertFalse(subnet.contains(InetAddress.getByName("fe80::1")));
        assertFalse(subnet.contains(InetAddress.getByName("10.0.0.1")));
    }

    @Test
    public void bareAddressIsASingleHost() throws Exception {
        Subnet subnet = Subnet.parse(" 10.0.0.5 ");

        assertEquals(32, subnet.prefixLength());
        assertTrue(subnet.contains(InetAddress.getByName("10.0.0.5")));
        assertFalse(subnet.contains(InetAddress.getByName("10.0.0.4")));
    }

    @Test
    public void zeroPrefixContainsEverythingOfItsFamily() throws Exception {
        Subnet subnet = Subnet.parse("0.0.0.0/0");

        assertTrue(subnet.contains(InetAddress.getByName("8.8.8.8")));
        assertFalse(subnet.contains(InetAddress.getByName("2001:db8::1")));
    }

    @Test
    public void ipv4MappedAddressesAreTreatedAsIpv4() throws Exception {
        InetAddress mapped = InetAddress.getByAddress(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte)0xff, (byte)0xff, 127, 0, 0, 1});

        assertTrue(Subnet.parse("127.0.0.1/32").contains(mapped));
        assertTrue(Subnet.parse("::ffff:192.168.1.1/120").contains(InetAddress.getByName("192.168.1.200")));
        assertFalse(Subnet.parse("::ffff:192.168.1.1/120").contains(InetAddress.getByName("192.168.2.1")));
        assertEquals(24, Subnet.parse("::ffff:192.168.1.1/120").prefixLength());
        assertThrows(ConfigurationException.class, () -> Subnet.parse("::ffff:192.168.1.1/64"));
    }

    @Test
    public void invalidSubnetsAreRejected() {
        for(String value : new String[] {"", "lan", "192.168.0.0/33", "::/129", "10.0.0.0/-1", "10.0.0.0/x", "10.0.0.0/", "example.com/24"}) {
            assertThrows(ConfigurationException.class, () -> Subnet.parse(value), value);
        }
    }
}
