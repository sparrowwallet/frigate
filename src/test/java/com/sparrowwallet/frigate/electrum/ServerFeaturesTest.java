package com.sparrowwallet.frigate.electrum;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ServerFeaturesTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void hostInfoDeserializesObjectShape() throws Exception {
        ServerFeatures.HostInfo info = MAPPER.readValue("{\"tcp_port\":50001,\"ssl_port\":50002}", ServerFeatures.HostInfo.class);
        Assertions.assertEquals(50001, info.tcp_port());
        Assertions.assertEquals(50002, info.ssl_port());
    }

    @Test
    public void hostInfoDeserializesBareIntegerAsTcpPort() throws Exception {
        ServerFeatures.HostInfo info = MAPPER.readValue("40001", ServerFeatures.HostInfo.class);
        Assertions.assertEquals(40001, info.tcp_port());
        Assertions.assertNull(info.ssl_port());
    }

    @Test
    public void serverFeaturesDeserializesMixedHostsMap() throws Exception {
        String json = "{\"hosts\":{\"a.example.com\":{\"tcp_port\":50001,\"ssl_port\":50002},\"b.example.com\":40001}}";
        ServerFeatures features = MAPPER.readValue(json, ServerFeatures.class);
        Assertions.assertEquals(50001, features.hosts().get("a.example.com").tcp_port());
        Assertions.assertEquals(50002, features.hosts().get("a.example.com").ssl_port());
        Assertions.assertEquals(40001, features.hosts().get("b.example.com").tcp_port());
        Assertions.assertNull(features.hosts().get("b.example.com").ssl_port());
    }
}
