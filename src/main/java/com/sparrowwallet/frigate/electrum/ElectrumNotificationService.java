package com.sparrowwallet.frigate.electrum;

import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcMethod;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcOptional;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcParam;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcService;
import com.sparrowwallet.frigate.index.TxEntry;

import java.util.List;

@JsonRpcService
public interface ElectrumNotificationService {
    @JsonRpcMethod("blockchain.headers.subscribe")
    void notifyHeaders(@JsonRpcParam("header") ElectrumBlockHeader electrumBlockHeader);

    @JsonRpcMethod("blockchain.scripthash.subscribe")
    void notifyScriptHash(@JsonRpcParam("scripthash") String scriptHash, @JsonRpcOptional @JsonRpcParam("status") String status);

    @JsonRpcMethod("blockchain.silentpayments.subscribe")
    void notifySilentPayments(@JsonRpcParam("subscription") SilentPaymentsSubscription silentPaymentsSubscription, @JsonRpcParam("progress") double progress, @JsonRpcParam("history") List<TxEntry> history);
}
