package com.sparrowwallet.frigate.electrum;

import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcMethod;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcOptional;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcParam;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcService;

import java.util.List;

//TODO (phase 1 step 20): notifications are sent with named params, the JSON-RPC client default, but the Electrum protocol uses
//positional params, and a null status is omitted rather than sent as null. Add @JsonRpcParams(ParamsType.ARRAY) and make the
//tests expect positional params (TestElectrumClient.param accepts both forms meanwhile).
@JsonRpcService
public interface ElectrumNotificationService {
    @JsonRpcMethod("blockchain.headers.subscribe")
    void notifyHeaders(@JsonRpcParam("header") ElectrumBlockHeader electrumBlockHeader);

    @JsonRpcMethod("blockchain.scripthash.subscribe")
    void notifyScriptHash(@JsonRpcParam("scripthash") String scriptHash, @JsonRpcOptional @JsonRpcParam("status") String status);

    @JsonRpcMethod("blockchain.silentpayments.subscribe")
    void notifySilentPayments(@JsonRpcParam("subscription") SilentPaymentsSubscription silentPaymentsSubscription, @JsonRpcParam("progress") double progress, @JsonRpcParam("history") List<SilentPaymentsTxEntry> history);
}
