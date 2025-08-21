package com.sparrowwallet.frigate.io;

import com.google.common.net.HostAndPort;

import java.util.Locale;

public enum Protocol {
    TCP(50001),
    SSL(50002),
    HTTP(80),
    HTTPS(443);

    private final int defaultPort;

    Protocol(int defaultPort) {
        this.defaultPort = defaultPort;
    }

    public int getDefaultPort() {
        return defaultPort;
    }

    public HostAndPort getServerHostAndPort(String url) {
        String lessProtocol = url.endsWith(":t") || url.endsWith(":s") ? url.substring(0, url.length() - 2) : url.substring(this.toUrlString().length());
        int pathStart = lessProtocol.indexOf('/');
        return HostAndPort.fromString(pathStart < 0 ? lessProtocol : lessProtocol.substring(0, pathStart));
    }

    public String toUrlString() {
        return toString().toLowerCase(Locale.ROOT) + "://";
    }

    public String toUrlString(String host) {
        return toUrlString(HostAndPort.fromHost(host));
    }

    public String toUrlString(String host, int port) {
        return toUrlString(HostAndPort.fromParts(host, port));
    }

    public String toUrlString(HostAndPort hostAndPort) {
        return toUrlString() + hostAndPort.toString();
    }

    public static boolean isOnionHost(String host) {
        return host != null && host.toLowerCase(Locale.ROOT).endsWith(".onion");
    }

    public static boolean isOnionAddress(Server server) {
        if(server != null) {
            return isOnionAddress(server.getHostAndPort());
        }

        return false;
    }

    public static boolean isOnionAddress(HostAndPort server) {
        return isOnionHost(server.getHost());
    }

    public static boolean isOnionAddress(String address) {
        if(address != null) {
            Protocol protocol = Protocol.getProtocol(address);
            if(protocol != null) {
                return isOnionAddress(protocol.getServerHostAndPort(address));
            }
        }

        return false;
    }

    public static Protocol getProtocol(String url) {
        if(url.startsWith("tcp://") || url.endsWith(":t")) {
            return TCP;
        }
        if(url.startsWith("ssl://") || url.endsWith(":s")) {
            return SSL;
        }
        if(url.startsWith("http://")) {
            return HTTP;
        }
        if(url.startsWith("https://")) {
            return HTTPS;
        }

        return null;
    }

    public static String getHost(String url) {
        Protocol protocol = getProtocol(url);
        if(protocol != null) {
            return protocol.getServerHostAndPort(url).getHost();
        }

        return null;
    }
}

