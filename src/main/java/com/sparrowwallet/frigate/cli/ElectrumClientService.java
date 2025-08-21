package com.sparrowwallet.frigate.cli;

import com.github.arteam.simplejsonrpc.client.JsonRpcParams;
import com.github.arteam.simplejsonrpc.client.ParamsType;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcMethod;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcOptional;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcParam;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcService;
import com.sparrowwallet.frigate.index.TxEntry;

import java.util.Collection;

@JsonRpcService
@JsonRpcParams(ParamsType.ARRAY)
public interface ElectrumClientService {
    @JsonRpcMethod("blockchain.silentpayments.get_history")
    Collection<TxEntry> getSilentPaymentsHistory(@JsonRpcParam("scanPrivateKey") String scanPrivateKey, @JsonRpcParam("spendPublicKey") String spendPublicKey, @JsonRpcParam("startHeight") @JsonRpcOptional Integer startHeight, @JsonRpcParam("endHeight") @JsonRpcOptional Integer endHeight);
}
