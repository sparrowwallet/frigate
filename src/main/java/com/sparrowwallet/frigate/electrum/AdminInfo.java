package com.sparrowwallet.frigate.electrum;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sparrowwallet.frigate.io.AggregateCounts;
import com.sparrowwallet.frigate.io.Config;

/**
 * The admin endpoint's getinfo response. It follows the same privacy rules as the stats log lines: figures about the server are
 * exact, while figures about its users are rounded to the nearest ten, and omitted below ten, so that polling it cannot follow
 * individual clients.
 *
 * @param backendTls how an ssl:// backend is authenticated, or null without an ssl:// backend
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminInfo(String version, String network, long uptimeSeconds, Health health, Usage usage, Config.LimitsConfig limits, String backendTls) {

    /**
     * @param backend the backend's state across sessions, or null without a backend: connected, partly disconnected or disconnected
     * @param tipHeight the chain tip height, or null without a Bitcoin Core connection, as are indexHeight and mempoolSize
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Health(String backend, Integer tipHeight, Integer indexHeight, Integer mempoolSize) {
        static Health of(ServerStats stats) {
            return new Health(stats.backendState(), stats.tipHeight(), stats.indexHeight(), stats.mempoolSize());
        }
    }

    /**
     * Connected at the time of the request, each rounded to the nearest ten, and null (omitted) below ten.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Usage(Long sessions, Long ips, Long scriptHashSubscriptions, Long silentPaymentsSubscriptions) {
        static Usage of(ServerStats stats) {
            return new Usage(rounded(stats.sessions()), rounded(stats.distinctIps()), rounded(stats.scriptHashSubscriptions()), rounded(stats.silentPaymentsSubscriptions()));
        }

        private static Long rounded(long raw) {
            long value = AggregateCounts.round(raw);
            return value > 0 ? value : null;
        }
    }
}
