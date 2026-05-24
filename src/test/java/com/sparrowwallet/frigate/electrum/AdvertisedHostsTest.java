package com.sparrowwallet.frigate.electrum;

import com.sparrowwallet.frigate.io.Server;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

public class AdvertisedHostsTest {
    @Test
    public void emptyInputYieldsEmptyMap() {
        Map<String, ServerFeatures.HostInfo> hosts = ElectrumServerService.buildAdvertisedHosts(List.of());
        Assertions.assertTrue(hosts.isEmpty());
    }

    @Test
    public void tcpAndSslOnSameHostMerge() {
        Map<String, ServerFeatures.HostInfo> hosts = ElectrumServerService.buildAdvertisedHosts(List.of(Server.fromString("tcp://example.com:50001"), Server.fromString("ssl://example.com:50002")));
        Assertions.assertEquals(1, hosts.size());
        ServerFeatures.HostInfo info = hosts.get("example.com");
        Assertions.assertEquals(50001, info.tcp_port());
        Assertions.assertEquals(50002, info.ssl_port());
    }

    @Test
    public void differentHostsRemainSeparate() {
        Map<String, ServerFeatures.HostInfo> hosts = ElectrumServerService.buildAdvertisedHosts(List.of(Server.fromString("tcp://example.com:50001"), Server.fromString("ssl://onion.example:443")));
        Assertions.assertEquals(2, hosts.size());
        Assertions.assertEquals(50001, hosts.get("example.com").tcp_port());
        Assertions.assertNull(hosts.get("example.com").ssl_port());
        Assertions.assertEquals(443, hosts.get("onion.example").ssl_port());
        Assertions.assertNull(hosts.get("onion.example").tcp_port());
    }

    @Test
    public void singleProtocolLeavesOtherNull() {
        Map<String, ServerFeatures.HostInfo> hosts = ElectrumServerService.buildAdvertisedHosts(List.of(Server.fromString("ssl://example.com:443")));
        ServerFeatures.HostInfo info = hosts.get("example.com");
        Assertions.assertNull(info.tcp_port());
        Assertions.assertEquals(443, info.ssl_port());
    }
}
