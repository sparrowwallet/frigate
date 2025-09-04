package com.sparrowwallet.frigate.electrum;

import com.github.arteam.simplejsonrpc.client.exception.JsonRpcException;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcMethod;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcOptional;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcParam;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcService;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.Version;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.silentpayments.SilentPaymentScanAddress;
import com.sparrowwallet.frigate.Frigate;
import com.sparrowwallet.frigate.bitcoind.BitcoindClient;
import com.sparrowwallet.frigate.bitcoind.BlockStats;
import com.sparrowwallet.frigate.bitcoind.FeeInfo;
import com.sparrowwallet.frigate.bitcoind.MempoolInfo;
import com.sparrowwallet.frigate.index.Index;
import com.sparrowwallet.frigate.index.TxEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.*;

@JsonRpcService
public class ElectrumServerService {
    private static final Logger log = LoggerFactory.getLogger(ElectrumServerService.class);
    private static final Version VERSION = new Version("1.4");
    private static final double DEFAULT_FEE_RATE = 0.00001d;

    private final BitcoindClient bitcoindClient;
    private final RequestHandler requestHandler;
    private final Index index;

    public ElectrumServerService(BitcoindClient bitcoindClient, RequestHandler requestHandler, Index index) {
        this.bitcoindClient = bitcoindClient;
        this.requestHandler = requestHandler;
        this.index = index;
    }

    @JsonRpcMethod("server.version")
    public List<String> getServerVersion(@JsonRpcParam("client_name") String clientName, @JsonRpcParam("protocol_version") String[] protocolVersion) throws UnsupportedVersionException {
        String version = protocolVersion.length > 1 ? protocolVersion[1] : protocolVersion[0];
        Version clientVersion = new Version(version);
        if(clientVersion.compareTo(VERSION) < 0) {
            throw new UnsupportedVersionException(version);
        }

        return List.of(Frigate.SERVER_NAME + " " + Frigate.SERVER_VERSION, VERSION.get());
    }

    @JsonRpcMethod("server.banner")
    public String getServerBanner() {
        return Frigate.SERVER_NAME + " " + Frigate.SERVER_VERSION + "\n" + bitcoindClient.getNetworkInfo().subversion() + (bitcoindClient.getNetworkInfo().networkactive() ? "" : " (disconnected)");
    }

    @JsonRpcMethod("blockchain.estimatefee")
    public Double estimateFee(@JsonRpcParam("number") int blocks) throws BitcoindIOException {
        try {
            FeeInfo feeInfo = bitcoindClient.getBitcoindService().estimateSmartFee(blocks);
            if(feeInfo == null || feeInfo.feerate() == null) {
                return DEFAULT_FEE_RATE;
            }

            return feeInfo.feerate();
        } catch(IllegalStateException e) {
            throw new BitcoindIOException(e);
        }
    }

    @JsonRpcMethod("mempool.get_fee_histogram")
    public List<List<Number>> getFeeHistogram() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @JsonRpcMethod("blockchain.relayfee")
    public Double getRelayFee() throws BitcoindIOException {
        try {
            MempoolInfo mempoolInfo = bitcoindClient.getBitcoindService().getMempoolInfo();
            return mempoolInfo.minrelaytxfee();
        } catch(IllegalStateException e) {
            throw new BitcoindIOException(e);
        }
    }

    @JsonRpcMethod("blockchain.headers.subscribe")
    public ElectrumBlockHeader subscribeHeaders() {
        requestHandler.setHeadersSubscribed(true);
        return bitcoindClient.getTip();
    }

    @JsonRpcMethod("server.ping")
    public void ping() throws BitcoindIOException {
        try {
            bitcoindClient.getBitcoindService().uptime();
        } catch(IllegalStateException e) {
            throw new BitcoindIOException(e);
        }
    }

    @JsonRpcMethod("blockchain.scripthash.subscribe")
    public String subscribeScriptHash(@JsonRpcParam("scripthash") String scriptHash) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @JsonRpcMethod("blockchain.scripthash.unsubscribe")
    public String unsubscribeScriptHash(@JsonRpcParam("scripthash") String scriptHash) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @JsonRpcMethod("blockchain.scripthash.get_history")
    public Collection<TxEntry> getHistory(@JsonRpcParam("scripthash") String scriptHash) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @JsonRpcMethod("blockchain.block.header")
    public String getBlockHeader(@JsonRpcParam("height") int height) throws BitcoindIOException, BlockNotFoundException {
        try {
            String blockHash = bitcoindClient.getBitcoindService().getBlockHash(height);
            return bitcoindClient.getBitcoindService().getBlockHeader(blockHash, false);
        } catch(JsonRpcException e) {
            throw new BlockNotFoundException(e.getErrorMessage());
        } catch(IllegalStateException e) {
            throw new BitcoindIOException(e);
        }
    }

    @JsonRpcMethod("blockchain.block.stats")
    public BlockStats getBlockStats(@JsonRpcParam("height") int height) throws BitcoindIOException, BlockNotFoundException {
        try {
            return bitcoindClient.getBitcoindService().getBlockStats(height);
        } catch(JsonRpcException e) {
            throw new BlockNotFoundException(e.getErrorMessage());
        } catch(IllegalStateException e) {
            throw new BitcoindIOException(e);
        }
    }

    @JsonRpcMethod("blockchain.transaction.get")
    @SuppressWarnings("unchecked")
    public Object getTransaction(@JsonRpcParam("tx_hash") String tx_hash, @JsonRpcParam("verbose") @JsonRpcOptional boolean verbose) throws BitcoindIOException, TransactionNotFoundException {
        if(verbose) {
            try {
                return bitcoindClient.getBitcoindService().getRawTransaction(tx_hash, true);
            } catch(JsonRpcException e) {
                try {
                    Map<String, Object> txInfo = bitcoindClient.getBitcoindService().getTransaction(tx_hash, true, true);
                    Object decoded = txInfo.get("decoded");
                    if(decoded instanceof Map<?, ?>) {
                        Map<String, Object> decodedMap = (Map<String, Object>)decoded;
                        decodedMap.put("hex", txInfo.get("hex"));
                        decodedMap.put("confirmations", txInfo.get("confirmations"));
                        decodedMap.put("blockhash", txInfo.get("blockhash"));
                        decodedMap.put("time", txInfo.get("time"));
                        decodedMap.put("blocktime", txInfo.get("blocktime"));
                        return decoded;
                    }
                    throw new TransactionNotFoundException(e.getErrorMessage());
                } catch(JsonRpcException ex) {
                    throw new TransactionNotFoundException(ex.getErrorMessage());
                } catch(IllegalStateException ex) {
                    throw new BitcoindIOException(ex);
                }
            } catch(IllegalStateException e) {
                throw new BitcoindIOException(e);
            }
        } else {
            try {
                return bitcoindClient.getBitcoindService().getTransaction(tx_hash, true, false).get("hex");
            } catch(JsonRpcException e) {
                try {
                    return bitcoindClient.getBitcoindService().getRawTransaction(tx_hash, false);
                } catch(JsonRpcException ex) {
                    throw new TransactionNotFoundException(ex.getErrorMessage());
                } catch(IllegalStateException ex) {
                    throw new BitcoindIOException(e);
                }
            } catch(IllegalStateException e) {
                throw new BitcoindIOException(e);
            }
        }
    }

    @JsonRpcMethod("blockchain.transaction.broadcast")
    public String broadcastTransaction(@JsonRpcParam("raw_tx") String rawTx) throws BitcoindIOException, BroadcastFailedException {
        try {
            return bitcoindClient.getBitcoindService().sendRawTransaction(rawTx, 0d);
        } catch(JsonRpcException e) {
            throw new BroadcastFailedException(e.getErrorMessage());
        } catch(IllegalStateException e) {
            throw new BitcoindIOException(e);
        }
    }

    @JsonRpcMethod("blockchain.silentpayments.subscribe")
    public String subscribeSilentPayments(@JsonRpcParam("scan_private_key") String scanPrivateKey, @JsonRpcParam("spend_public_key") String spendPublicKey, @JsonRpcParam("start") @JsonRpcOptional Long start) {
        SilentPaymentScanAddress silentPaymentScanAddress = getSilentPaymentScanAddress(scanPrivateKey, spendPublicKey);
        requestHandler.subscribeSilentPaymentsAddress(silentPaymentScanAddress);

        int startHeight = getStartHeight(start);
        index.startHistoryScan(silentPaymentScanAddress, startHeight, null, new WeakReference<>(requestHandler));

        return silentPaymentScanAddress.getAddress();
    }

    @JsonRpcMethod("blockchain.silentpayments.unsubscribe")
    public String unsubscribeSilentPayments(@JsonRpcParam("scan_private_key") String scanPrivateKey, @JsonRpcParam("spend_public_key") String spendPublicKey) {
        SilentPaymentScanAddress silentPaymentScanAddress = getSilentPaymentScanAddress(scanPrivateKey, spendPublicKey);
        requestHandler.unsubscribeSilentPaymentsAddress(silentPaymentScanAddress);

        return silentPaymentScanAddress.getAddress();
    }

    private static SilentPaymentScanAddress getSilentPaymentScanAddress(String scanPrivateKey, String spendPublicKey) {
        ECKey scanKey = ECKey.fromPrivate(Utils.hexToBytes(scanPrivateKey));
        ECKey spendKey = ECKey.fromPublicOnly(Utils.hexToBytes(spendPublicKey));
        return SilentPaymentScanAddress.from(scanKey, spendKey);
    }

    private int getStartHeight(Long start) {
        int startHeight = 0;
        if(start != null) {
            if(start > Transaction.MAX_BLOCK_LOCKTIME) {
                startHeight = bitcoindClient.findBlockByTimestamp(start);
            } else if(start > 0) {
                startHeight = start.intValue();
            }
        }
        return startHeight;
    }
}
