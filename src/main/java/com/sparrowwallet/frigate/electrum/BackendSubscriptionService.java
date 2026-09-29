package com.sparrowwallet.frigate.electrum;

import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcMethod;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcOptional;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcParam;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcService;

import java.util.function.BiConsumer;

/**
 * Handles notifications arriving on one session's backend connection. The connection belongs to exactly one session, so each
 * notification is delivered directly to that session rather than broadcast to all sessions for filtering.
 */
@JsonRpcService
public class BackendSubscriptionService {
    private final ScriptHashSubscriptions scriptHashSubscriptions;
    private final BiConsumer<String, String> scriptHashNotifier;

    public BackendSubscriptionService(ScriptHashSubscriptions scriptHashSubscriptions, BiConsumer<String, String> scriptHashNotifier) {
        this.scriptHashSubscriptions = scriptHashSubscriptions;
        this.scriptHashNotifier = scriptHashNotifier;
    }

    @JsonRpcMethod("blockchain.scripthash.subscribe")
    public void scriptHashStatusUpdated(@JsonRpcParam("scripthash") final String scriptHash, @JsonRpcOptional @JsonRpcParam("status") final String status) {
        if(scriptHashSubscriptions.recordNotification(scriptHash, status)) {
            scriptHashNotifier.accept(scriptHash, status);
        }
    }
}
