package com.sparrowwallet.frigate.io;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import com.sparrowwallet.frigate.ConfigurationException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class ServerConfigHostTest {
    private static final TomlMapper MAPPER = TomlMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    @Test
    public void unsetHostAdvertisesNothing() throws Exception {
        Config.ServerConfig config = parse("");
        Assertions.assertTrue(config.getAdvertisedHosts().isEmpty());
    }

    @Test
    public void emptyStringHostAdvertisesNothing() throws Exception {
        Config.ServerConfig config = parse("host = \"\"");
        Assertions.assertTrue(config.getAdvertisedHosts().isEmpty());
    }

    @Test
    public void bareHostnameExpandsToBindPorts() throws Exception {
        Config.ServerConfig config = parse("""
                host = "example.com"
                tcp = "tcp://0.0.0.0:50001"
                ssl = "ssl://0.0.0.0:50002"
                """);
        List<Server> advertised = config.getAdvertisedHosts();
        Assertions.assertEquals(2, advertised.size());
        Assertions.assertEquals("tcp://example.com:50001", advertised.get(0).getUrl());
        Assertions.assertEquals("ssl://example.com:50002", advertised.get(1).getUrl());
    }

    @Test
    public void bareHostnameWithOnlyTcpBindAdvertisesTcpOnly() throws Exception {
        Config.ServerConfig config = parse("""
                host = "example.com"
                tcp = "tcp://0.0.0.0:50001"
                """);
        List<Server> advertised = config.getAdvertisedHosts();
        Assertions.assertEquals(1, advertised.size());
        Assertions.assertEquals(Protocol.TCP, advertised.getFirst().getProtocol());
        Assertions.assertEquals(50001, advertised.getFirst().getHostAndPort().getPort());
    }

    @Test
    public void urlFormUsesExplicitPort() throws Exception {
        Config.ServerConfig config = parse("""
                host = "ssl://example.com:443"
                tcp = "tcp://0.0.0.0:50001"
                """);
        List<Server> advertised = config.getAdvertisedHosts();
        Assertions.assertEquals(1, advertised.size());
        Assertions.assertEquals(Protocol.SSL, advertised.getFirst().getProtocol());
        Assertions.assertEquals(443, advertised.getFirst().getHostAndPort().getPort());
    }

    @Test
    public void arrayFormAdvertisesEachEntry() throws Exception {
        Config.ServerConfig config = parse("""
                host = ["tcp://example.com:80", "ssl://example.com:443"]
                """);
        List<Server> advertised = config.getAdvertisedHosts();
        Assertions.assertEquals(2, advertised.size());
        Assertions.assertEquals("tcp://example.com:80", advertised.get(0).getUrl());
        Assertions.assertEquals("ssl://example.com:443", advertised.get(1).getUrl());
    }

    @Test
    public void mixedArrayAcceptsBareAndUrl() throws Exception {
        Config.ServerConfig config = parse("""
                host = ["example.com", "ssl://onion.example:443"]
                tcp = "tcp://0.0.0.0:50001"
                """);
        List<Server> advertised = config.getAdvertisedHosts();
        Assertions.assertEquals(2, advertised.size());
        Assertions.assertEquals("tcp://example.com:50001", advertised.get(0).getUrl());
        Assertions.assertEquals("ssl://onion.example:443", advertised.get(1).getUrl());
    }

    @Test
    public void httpSchemeIsRejected() throws Exception {
        Config.ServerConfig config = parse("host = \"http://example.com:80\"");
        Assertions.assertThrows(ConfigurationException.class, config::getAdvertisedHosts);
    }

    @Test
    public void urlWithoutPortIsRejected() throws Exception {
        Config.ServerConfig config = parse("host = \"tcp://example.com\"");
        Assertions.assertThrows(ConfigurationException.class, config::getAdvertisedHosts);
    }

    private static Config.ServerConfig parse(String tomlBody) throws Exception {
        String toml = "[server]\n" + tomlBody;
        Config config = MAPPER.readValue(toml, Config.class);
        return config.getServer();
    }
}
