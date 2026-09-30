package com.sparrowwallet.frigate.io;

import com.google.common.net.InetAddresses;
import com.sparrowwallet.frigate.ConfigurationException;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * An IPv4 or IPv6 subnet in CIDR notation (such as 192.168.0.0/16 or fd00::/8), or a single address.
 */
public record Subnet(byte[] network, int prefixLength) {
    /**
     * @throws ConfigurationException if the value is not a valid address or CIDR subnet
     */
    public static Subnet parse(String value) {
        String trimmed = value.trim();
        int slash = trimmed.indexOf('/');
        String address = slash < 0 ? trimmed : trimmed.substring(0, slash);
        if(!InetAddresses.isInetAddress(address)) {
            throw new ConfigurationException("Invalid subnet '" + value + "': expected an IP address or CIDR subnet such as 192.168.0.0/16");
        }

        byte[] network = normalize(InetAddresses.forString(address)).getAddress();
        int maxPrefix = address.contains(":") ? 128 : 32;
        int prefixLength = maxPrefix;
        if(slash >= 0) {
            try {
                prefixLength = Integer.parseInt(trimmed.substring(slash + 1));
            } catch(NumberFormatException e) {
                prefixLength = -1;
            }
            if(prefixLength < 0 || prefixLength > maxPrefix) {
                throw new ConfigurationException("Invalid subnet '" + value + "': prefix length must be between 0 and " + maxPrefix);
            }
        }
        //an IPv4-mapped subnet's prefix counts the 96 bits of the mapping
        int mappedBits = maxPrefix - network.length * 8;
        if(prefixLength < mappedBits) {
            throw new ConfigurationException("Invalid subnet '" + value + "': an IPv4-mapped prefix length must be at least " + mappedBits);
        }
        return new Subnet(network, prefixLength - mappedBits);
    }

    public boolean contains(InetAddress address) {
        byte[] bytes = normalize(address).getAddress();
        if(bytes.length != network.length) {
            return false;
        }
        int fullBytes = prefixLength / 8;
        for(int i = 0; i < fullBytes; i++) {
            if(bytes[i] != network[i]) {
                return false;
            }
        }
        int remainingBits = prefixLength % 8;
        if(remainingBits == 0) {
            return true;
        }
        int mask = 0xff << (8 - remainingBits);
        return (bytes[fullBytes] & mask) == (network[fullBytes] & mask);
    }

    /**
     * Treats an IPv4-mapped IPv6 address as the IPv4 address it maps.
     */
    static InetAddress normalize(InetAddress address) {
        byte[] bytes = address.getAddress();
        if(address instanceof Inet6Address && bytes.length == 16) {
            boolean mapped = bytes[10] == (byte)0xff && bytes[11] == (byte)0xff;
            for(int i = 0; i < 10 && mapped; i++) {
                mapped = bytes[i] == 0;
            }
            if(mapped) {
                try {
                    return Inet4Address.getByAddress(new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]});
                } catch(UnknownHostException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return address;
    }
}
