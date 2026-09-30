package com.sparrowwallet.frigate.io;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class LimitsConfigTest {
    private static final TomlMapper MAPPER = TomlMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private static Config.LimitsConfig parse(String toml) throws Exception {
        return MAPPER.readValue(toml, Config.class).getLimits();
    }

    private static void assertDefaults(Config.LimitsConfig limits) {
        assertEquals(Config.LimitsConfig.DEFAULT_MAX_CONNECTIONS, limits.getMaxConnections());
        assertEquals(Config.LimitsConfig.DEFAULT_MAX_CONNECTIONS_PER_IP, limits.getMaxConnectionsPerIp());
        assertEquals(Config.LimitsConfig.DEFAULT_MAX_REQUEST_BYTES, limits.getMaxRequestBytes());
        assertEquals(Config.LimitsConfig.DEFAULT_MAX_BATCH_SIZE, limits.getMaxBatchSize());
        assertEquals(Config.LimitsConfig.DEFAULT_SESSION_TIMEOUT_SECONDS, limits.getSessionTimeoutSeconds());
        assertEquals(Config.LimitsConfig.DEFAULT_MAX_SUBSCRIPTIONS_PER_IP, limits.getMaxSubscriptionsPerIp());
        assertEquals(Config.LimitsConfig.DEFAULT_MAX_SUBSCRIPTIONS, limits.getMaxSubscriptions());
        assertEquals(Config.LimitsConfig.DEFAULT_NOTIFICATION_QUEUE_SIZE, limits.getNotificationQueueSize());
        assertEquals(Config.LimitsConfig.DEFAULT_SILENT_PAYMENTS_SUBSCRIBE_BURST, limits.getSilentPaymentsSubscribeBurst());
        assertEquals(Config.LimitsConfig.DEFAULT_SILENT_PAYMENTS_SUBSCRIBE_INTERVAL_SECONDS, limits.getSilentPaymentsSubscribeIntervalSeconds());
    }

    @Test
    public void absentSectionUsesDefaults() throws Exception {
        assertDefaults(parse("[server]\ntcp = \"tcp://127.0.0.1:50001\"\n"));
        assertDefaults(new Config().getLimits());
    }

    @Test
    public void configuredValuesAreUsed() throws Exception {
        Config.LimitsConfig limits = parse("""
                [limits]
                maxConnections = 50
                maxConnectionsPerIp = 3
                maxRequestBytes = 2000
                maxBatchSize = 10
                sessionTimeoutSeconds = 30
                maxSubscriptionsPerIp = 1500
                maxSubscriptions = 5000000000
                notificationQueueSize = 20
                silentPaymentsSubscribeBurst = 40
                silentPaymentsSubscribeIntervalSeconds = 4
                """);

        assertEquals(50, limits.getMaxConnections());
        assertEquals(3, limits.getMaxConnectionsPerIp());
        assertEquals(2000, limits.getMaxRequestBytes());
        assertEquals(10, limits.getMaxBatchSize());
        assertEquals(30, limits.getSessionTimeoutSeconds());
        assertEquals(1500, limits.getMaxSubscriptionsPerIp());
        assertEquals(5_000_000_000L, limits.getMaxSubscriptions());
        assertEquals(20, limits.getNotificationQueueSize());
        assertEquals(40, limits.getSilentPaymentsSubscribeBurst());
        assertEquals(4, limits.getSilentPaymentsSubscribeIntervalSeconds());
    }

    @Test
    public void nonPositiveValuesUseDefaults() throws Exception {
        Config.LimitsConfig limits = parse("""
                [limits]
                maxConnections = 0
                maxConnectionsPerIp = -1
                maxRequestBytes = 0
                maxBatchSize = 0
                sessionTimeoutSeconds = -5
                maxSubscriptionsPerIp = 0
                maxSubscriptions = 0
                notificationQueueSize = 0
                silentPaymentsSubscribeBurst = 0
                silentPaymentsSubscribeIntervalSeconds = -1
                """);

        assertDefaults(limits);
    }

    @Test
    public void sslReloadSecondsIsParsed() throws Exception {
        assertEquals(60, MAPPER.readValue("[server]\nsslReloadSeconds = 60\n", Config.class).getServer().getSslReloadSeconds());
        assertEquals(Config.ServerConfig.DEFAULT_SSL_RELOAD_SECONDS, MAPPER.readValue("[server]\nsslReloadSeconds = 0\n", Config.class).getServer().getSslReloadSeconds());
    }

    @Test
    public void healthStatsEnabledByDefault() throws Exception {
        assertTrue(new Config().getServer().isHealthStatsEnabled());
        assertFalse(MAPPER.readValue("[server]\nhealthStatsEnabled = false\n", Config.class).getServer().isHealthStatsEnabled());
    }

    @Test
    public void shutdownDrainSecondsIsParsed() throws Exception {
        assertEquals(Config.ServerConfig.DEFAULT_SHUTDOWN_DRAIN_SECONDS, new Config().getServer().getShutdownDrainSeconds());
        assertEquals(30, MAPPER.readValue("[server]\nshutdownDrainSeconds = 30\n", Config.class).getServer().getShutdownDrainSeconds());
        //0 closes sessions at once
        assertEquals(0, MAPPER.readValue("[server]\nshutdownDrainSeconds = 0\n", Config.class).getServer().getShutdownDrainSeconds());
    }

    @Test
    public void donationAddressAndBannerFileAreParsed() throws Exception {
        Config.ServerConfig unset = new Config().getServer();
        assertEquals("", unset.getDonationAddress());
        assertNull(unset.getBannerFileObj());

        Config.ServerConfig set = MAPPER.readValue("[server]\ndonationAddress = \" bc1qexample \"\nbannerFile = \"/etc/frigate/banner.txt\"\n", Config.class).getServer();
        assertEquals("bc1qexample", set.getDonationAddress());
        assertEquals(new java.io.File("/etc/frigate/banner.txt"), set.getBannerFileObj());
    }

    @Test
    public void excludedSubnetsAreParsed() throws Exception {
        assertEquals(List.of("127.0.0.1/32", "::1/128"), new Config().getLimits().getExcludedSubnets());
        assertEquals(2, new Config().getLimits().getExcludedSubnetList().size());

        Config.LimitsConfig home = parse("[limits]\nexcludedSubnets = [\"127.0.0.1/32\", \"192.168.0.0/16\", \"fd00::/8\", \"10.0.0.5\"]\n");
        List<Subnet> subnets = home.getExcludedSubnetList();
        assertEquals(4, subnets.size());
        assertTrue(subnets.get(1).contains(java.net.InetAddress.getByName("192.168.44.3")));
        assertTrue(subnets.get(2).contains(java.net.InetAddress.getByName("fd12::1")));
        assertTrue(subnets.get(3).contains(java.net.InetAddress.getByName("10.0.0.5")));
        assertFalse(subnets.get(3).contains(java.net.InetAddress.getByName("10.0.0.6")));

        //an empty list exempts no one
        assertEquals(List.of(), parse("[limits]\nexcludedSubnets = []\n").getExcludedSubnetList());

        //an invalid subnet fails when the server starts
        assertThrows(com.sparrowwallet.frigate.ConfigurationException.class, () -> parse("[limits]\nexcludedSubnets = [\"192.168.0.0/33\"]\n").getExcludedSubnetList());
        assertThrows(com.sparrowwallet.frigate.ConfigurationException.class, () -> parse("[limits]\nexcludedSubnets = [\"lan\"]\n").getExcludedSubnetList());
    }

    @Test
    public void defaultConfigFileParses() throws Exception {
        try(InputStream in = Config.class.getResourceAsStream("/config.toml.default")) {
            assertNotNull(in);
            Config config = MAPPER.readValue(in, Config.class);
            assertDefaults(config.getLimits());
            assertEquals(Config.ServerConfig.DEFAULT_BACKEND_REQUEST_TIMEOUT_SECONDS, config.getServer().getBackendRequestTimeoutSeconds());
            assertEquals(Config.ServerConfig.DEFAULT_SSL_RELOAD_SECONDS, config.getServer().getSslReloadSeconds());
        }
    }
}
