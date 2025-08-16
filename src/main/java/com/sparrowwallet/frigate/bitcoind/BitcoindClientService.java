package com.sparrowwallet.frigate.bitcoind;

import com.github.arteam.simplejsonrpc.client.JsonRpcParams;
import com.github.arteam.simplejsonrpc.client.ParamsType;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcMethod;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcOptional;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcParam;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcService;
import com.sparrowwallet.drongo.protocol.Sha256Hash;

import java.util.Map;
import java.util.Set;

@JsonRpcService
@JsonRpcParams(ParamsType.ARRAY)
public interface BitcoindClientService {
    @JsonRpcMethod("uptime")
    long uptime();

    @JsonRpcMethod("getnetworkinfo")
    NetworkInfo getNetworkInfo();

    @JsonRpcMethod("estimatesmartfee")
    FeeInfo estimateSmartFee(@JsonRpcParam("conf_target") int blocks);

    @JsonRpcMethod("getrawmempool")
    Set<Sha256Hash> getRawMempool();

    @JsonRpcMethod("getrawmempool")
    Map<Sha256Hash, MempoolEntry> getRawMempool(@JsonRpcParam("verbose") boolean verbose);

    @JsonRpcMethod("getmempoolinfo")
    MempoolInfo getMempoolInfo();

    @JsonRpcMethod("getblockchaininfo")
    BlockchainInfo getBlockchainInfo();

    @JsonRpcMethod("getblockhash")
    String getBlockHash(@JsonRpcParam("height") int height);

    @JsonRpcMethod("getblockheader")
    String getBlockHeader(@JsonRpcParam("blockhash") String blockhash, @JsonRpcParam("verbose") boolean verbose);

    @JsonRpcMethod("getblockheader")
    VerboseBlockHeader getBlockHeader(@JsonRpcParam("blockhash") String blockhash);

    @JsonRpcMethod("getblockstats")
    BlockStats getBlockStats(@JsonRpcParam("blockhash") int hash_or_height);

    @JsonRpcMethod("getblock")
    Object getBlock(@JsonRpcParam("blockhash") String blockhash, @JsonRpcOptional @JsonRpcParam("verbosity") int verbosity);

    @JsonRpcMethod("getrawtransaction")
    Object getRawTransaction(@JsonRpcParam("txid") String txid, @JsonRpcParam("verbose") boolean verbose);

    @JsonRpcMethod("gettransaction")
    Map<String, Object> getTransaction(@JsonRpcParam("txid") String txid, @JsonRpcParam("include_watchonly") boolean includeWatchOnly, @JsonRpcParam("verbose") boolean verbose);

    @JsonRpcMethod("getmempoolentry")
    MempoolEntry getMempoolEntry(@JsonRpcParam("txid") String txid);

    @JsonRpcMethod("sendrawtransaction")
    String sendRawTransaction(@JsonRpcParam("hexstring") String rawTx, @JsonRpcParam("maxfeerate") Double maxFeeRate);
}

