package com.sparrowwallet.frigate.electrum;

import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcError;

/**
 * A subscription refused because a session, per-IP or global subscription limit has been reached. Reported with a distinct
 * error code so wallets can tell it apart from a failure worth retrying.
 */
@JsonRpcError(code = -32005, message = "subscription limit exceeded")
public class SubscriptionLimitException extends Exception {
    public SubscriptionLimitException(String message) {
        super(message);
    }
}
