package com.sparrowwallet.frigate.cli;

import com.github.arteam.simplejsonrpc.client.JsonRpcParams;
import com.github.arteam.simplejsonrpc.client.ParamsType;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcMethod;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcOptional;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcParam;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcService;
import com.sparrowwallet.frigate.index.TweakEntry;
import com.sparrowwallet.frigate.index.TxEntry;

import java.util.Collection;

@JsonRpcService
@JsonRpcParams(ParamsType.ARRAY)
public interface ElectrumClientService {
    @JsonRpcMethod("blockchain.silentpayments.subscribe")
    String subscribeSilentPayments(@JsonRpcParam("scan_private_key") String scanPrivateKey, @JsonRpcParam("spend_public_key") String spendPublicKey, @JsonRpcParam("start") @JsonRpcOptional Long start);

    @JsonRpcMethod("blockchain.block.tweaks")
    Collection<TweakEntry> getTweaksByHeight(@JsonRpcParam("block_height") int blockHeight);
}
