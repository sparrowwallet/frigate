package com.sparrowwallet.frigate.electrum;

import com.github.arteam.simplejsonrpc.client.JsonRpcParams;
import com.github.arteam.simplejsonrpc.client.ParamsType;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcMethod;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcOptional;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcParam;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcService;

import java.util.List;

/**
 * Notifications sent to clients. Their params are positional, as the Electrum protocol specifies and Electrum servers send them,
 * rather than named, the JSON-RPC client library's default; a null status is sent explicitly as null.
 */
@JsonRpcService
@JsonRpcParams(ParamsType.ARRAY)
public interface ElectrumNotificationService {
    @JsonRpcMethod("blockchain.headers.subscribe")
    void notifyHeaders(@JsonRpcParam("header") ElectrumBlockHeader electrumBlockHeader);

    @JsonRpcMethod("blockchain.scripthash.subscribe")
    void notifyScriptHash(@JsonRpcParam("scripthash") String scriptHash, @JsonRpcOptional @JsonRpcParam("status") String status);

    @JsonRpcMethod("blockchain.silentpayments.subscribe")
    void notifySilentPayments(@JsonRpcParam("subscription") SilentPaymentsSubscription silentPaymentsSubscription, @JsonRpcParam("progress") double progress, @JsonRpcParam("history") List<SilentPaymentsTxEntry> history);
}
