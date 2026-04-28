package com.sparrowwallet.frigate.electrum;

import com.github.arteam.simplejsonrpc.client.JsonRpcParams;
import com.github.arteam.simplejsonrpc.client.ParamsType;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcMethod;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcOptional;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcParam;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcService;

import java.util.Collection;
import java.util.List;

@JsonRpcService
@JsonRpcParams(ParamsType.ARRAY)
public interface ElectrumBackendService {
    @JsonRpcMethod("server.version")
    List<String> getServerVersion(@JsonRpcParam("client_name") String clientName, @JsonRpcParam("protocol_version") Object protocolVersion);

    @JsonRpcMethod("server.features")
    ServerFeatures getServerFeatures();

    @JsonRpcMethod("server.add_peer")
    boolean addPeer(@JsonRpcParam("features") ServerFeatures features);

    @JsonRpcMethod("server.donation_address")
    String getDonationAddress();

    @JsonRpcMethod("server.peers.subscribe")
    List<ServerPeer> subscribePeers();

    @JsonRpcMethod("mempool.get_fee_histogram")
    List<List<Number>> getFeeHistogram();

    @JsonRpcMethod("blockchain.scripthash.subscribe")
    String subscribeScriptHash(@JsonRpcParam("scripthash") String scriptHash);

    @JsonRpcMethod("blockchain.scripthash.unsubscribe")
    String unsubscribeScriptHash(@JsonRpcParam("scripthash") String scriptHash);

    @JsonRpcMethod("blockchain.scripthash.get_balance")
    ScriptHashBalance getBalance(@JsonRpcParam("scripthash") String scriptHash);

    @JsonRpcMethod("blockchain.scripthash.get_history")
    Collection<TxEntry> getHistory(@JsonRpcParam("scripthash") String scriptHash);

    @JsonRpcMethod("blockchain.scripthash.get_mempool")
    Collection<TxEntry> getMempool(@JsonRpcParam("scripthash") String scriptHash);

    @JsonRpcMethod("blockchain.scripthash.listunspent")
    Collection<UnspentOutput> listUnspent(@JsonRpcParam("scripthash") String scriptHash);

    @JsonRpcMethod("blockchain.block.header")
    Object getBlockHeader(@JsonRpcParam("height") int height, @JsonRpcParam("cp_height") Integer cpHeight);

    @JsonRpcMethod("blockchain.block.headers")
    Object getBlockHeaders(@JsonRpcParam("start_height") int startHeight, @JsonRpcParam("count") int count);

    @JsonRpcMethod("blockchain.block.headers")
    Object getBlockHeaders(@JsonRpcParam("start_height") int startHeight, @JsonRpcParam("count") int count, @JsonRpcParam("cp_height") Integer cpHeight);

    @JsonRpcMethod("blockchain.transaction.get_merkle")
    TransactionMerkle getTransactionMerkle(@JsonRpcParam("tx_hash") String txHash, @JsonRpcParam("height") int height);

    @JsonRpcMethod("blockchain.transaction.id_from_pos")
    Object getTransactionIdFromPos(@JsonRpcParam("height") int height, @JsonRpcParam("tx_pos") int txPos, @JsonRpcParam("merkle") @JsonRpcOptional Boolean merkle);
}
