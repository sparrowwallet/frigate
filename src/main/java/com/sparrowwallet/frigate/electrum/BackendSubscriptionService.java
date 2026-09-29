package com.sparrowwallet.frigate.electrum;

import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcMethod;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcOptional;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcParam;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcService;

/**
 * Handles notifications arriving on one session's backend connection. The connection belongs to exactly one session, so each
 * notification is delivered directly to that session rather than broadcast to all sessions for filtering.
 */
@JsonRpcService
public class BackendSubscriptionService {
    private final ScriptHashSubscriptions scriptHashSubscriptions;
    private final ScriptHashNotifier scriptHashNotifier;

    public BackendSubscriptionService(ScriptHashSubscriptions scriptHashSubscriptions, ScriptHashNotifier scriptHashNotifier) {
        this.scriptHashSubscriptions = scriptHashSubscriptions;
        this.scriptHashNotifier = scriptHashNotifier;
    }

    /**
     * Called on the backend reader thread, where ElectrumTransport.getReadSequence() is this notification's sequence.
     */
    @JsonRpcMethod("blockchain.scripthash.subscribe")
    public void scriptHashStatusUpdated(@JsonRpcParam("scripthash") final String scriptHash, @JsonRpcOptional @JsonRpcParam("status") final String status) {
        long sequence = ElectrumTransport.getReadSequence();
        if(scriptHashSubscriptions.recordNotification(scriptHash, status, sequence)) {
            scriptHashNotifier.notifyScriptHash(scriptHash, status, sequence);
        }
    }

    @FunctionalInterface
    public interface ScriptHashNotifier {
        void notifyScriptHash(String scriptHash, String status, long sequence);
    }
}
