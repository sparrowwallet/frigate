package com.sparrowwallet.frigate.electrum;

import com.sparrowwallet.frigate.bitcoind.BitcoindClient;

import java.util.Collection;

/**
 * A snapshot of the Electrum server's state, for the stats log lines and the admin endpoint. Counts of events (notifications,
 * reconnects, timeouts) are cumulative for the life of the process; the log lines report their change.
 *
 * @param backendConfigured whether a backend Electrum server is configured
 * @param tipHeight the chain tip height, or null without a Bitcoin Core connection, as are indexHeight and mempoolSize
 */
public record ServerStats(int sessions, int distinctIps, long scriptHashSubscriptions, int silentPaymentsSubscriptions,
                          int notifierQueueDepth, long notificationsDelivered,
                          boolean backendConfigured, int backendSessions, int backendConnected, long backendReconnects, long backendTimeouts,
                          Integer tipHeight, Integer indexHeight, Integer mempoolSize) {

    public static ServerStats collect(Collection<RequestHandler> sessions, ConnectionGate connectionGate, boolean backendConfigured, BitcoindClient bitcoindClient) {
        int count = 0;
        int silentPaymentsSubscriptions = 0;
        int notifierQueueDepth = 0;
        int backendSessions = 0;
        int backendConnected = 0;
        for(RequestHandler session : sessions) {
            count++;
            silentPaymentsSubscriptions += session.getSilentPaymentsSubscriptionCount();
            notifierQueueDepth = Math.max(notifierQueueDepth, session.getNotifierQueueDepth());
            if(session.hasBackend()) {
                backendSessions++;
                if(session.isBackendConnected()) {
                    backendConnected++;
                }
            }
        }

        Integer tipHeight = null;
        Integer indexHeight = null;
        Integer mempoolSize = null;
        if(bitcoindClient != null && bitcoindClient.getTip() != null) {
            tipHeight = bitcoindClient.getTip().height();
            indexHeight = bitcoindClient.getIndexedHeight();
            mempoolSize = bitcoindClient.getMempoolSize();
        }

        return new ServerStats(count, connectionGate.getDistinctIpCount(), connectionGate.getSubscriptionCount(), silentPaymentsSubscriptions,
                notifierQueueDepth, ServerMetrics.getNotificationsDelivered(),
                backendConfigured, backendSessions, backendConnected, ServerMetrics.getBackendReconnects(), ServerMetrics.getBackendTimeouts(),
                tipHeight, indexHeight, mempoolSize);
    }
}
