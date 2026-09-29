package com.sparrowwallet.frigate.io;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

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
        assertEquals(Config.LimitsConfig.DEFAULT_MAX_SUBSCRIPTIONS_PER_SESSION, limits.getMaxSubscriptionsPerSession());
        assertEquals(Config.LimitsConfig.DEFAULT_MAX_SUBSCRIPTIONS_PER_IP, limits.getMaxSubscriptionsPerIp());
        assertEquals(Config.LimitsConfig.DEFAULT_MAX_SUBSCRIPTIONS, limits.getMaxSubscriptions());
        assertEquals(Config.LimitsConfig.DEFAULT_NOTIFICATION_QUEUE_SIZE, limits.getNotificationQueueSize());
        assertEquals(Config.LimitsConfig.DEFAULT_REQUEST_TOKENS, limits.getRequestTokens());
        assertEquals(Config.LimitsConfig.DEFAULT_REQUEST_TOKENS_PER_SECOND, limits.getRequestTokensPerSecond());
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
                maxSubscriptionsPerSession = 500
                maxSubscriptionsPerIp = 1500
                maxSubscriptions = 5000000000
                notificationQueueSize = 20
                requestTokens = 40
                requestTokensPerSecond = 4
                """);

        assertEquals(50, limits.getMaxConnections());
        assertEquals(3, limits.getMaxConnectionsPerIp());
        assertEquals(2000, limits.getMaxRequestBytes());
        assertEquals(10, limits.getMaxBatchSize());
        assertEquals(30, limits.getSessionTimeoutSeconds());
        assertEquals(500, limits.getMaxSubscriptionsPerSession());
        assertEquals(1500, limits.getMaxSubscriptionsPerIp());
        assertEquals(5_000_000_000L, limits.getMaxSubscriptions());
        assertEquals(20, limits.getNotificationQueueSize());
        assertEquals(40, limits.getRequestTokens());
        assertEquals(4, limits.getRequestTokensPerSecond());
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
                maxSubscriptionsPerSession = 0
                maxSubscriptionsPerIp = 0
                maxSubscriptions = 0
                notificationQueueSize = 0
                requestTokens = 0
                requestTokensPerSecond = -1
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
