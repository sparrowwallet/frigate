package com.sparrowwallet.frigate.electrum;

import com.github.arteam.simplejsonrpc.client.JsonRpcClient;
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
import com.sparrowwallet.frigate.bitcoind.*;
import com.sparrowwallet.frigate.index.IndexQuerier;
import com.sparrowwallet.frigate.index.TxEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.stream.Collectors;

@JsonRpcService
public class ElectrumServerService {
    private static final Logger log = LoggerFactory.getLogger(ElectrumServerService.class);
    private static final Version MIN_VERSION = new Version("1.4");
    private static final double DEFAULT_FEE_RATE = 0.00001d;
    public static final Version MAX_DEFAULT_VERSION = new Version("1.4.2");
    public static final Version MAX_SUBMIT_PACKAGE_VERSION = new Version("1.6");

    private final BitcoindClient bitcoindClient;
    private final RequestHandler requestHandler;
    private final IndexQuerier indexQuerier;
    private final ElectrumBackendService electrumBackendService;
    private Version protocolVersion;

    public ElectrumServerService(BitcoindClient bitcoindClient, RequestHandler requestHandler, IndexQuerier indexQuerier, ElectrumTransport backendTransport) {
        this.bitcoindClient = bitcoindClient;
        this.requestHandler = requestHandler;
        this.indexQuerier = indexQuerier;

        if(backendTransport != null) {
            JsonRpcClient jsonRpcClient = new JsonRpcClient(backendTransport);
            this.electrumBackendService = jsonRpcClient.onDemand(ElectrumBackendService.class);
        } else {
            electrumBackendService = null;
        }
    }

    public IndexQuerier getIndexQuerier() {
        return indexQuerier;
    }

    private void checkVersionNegotiated() {
        if(protocolVersion == null) {
            throw new VersionNotNegotiatedException();
        }
    }

    private Version getMaxSupportedVersion() {
        return bitcoindClient != null && bitcoindClient.containsSubmitPackage() ? MAX_SUBMIT_PACKAGE_VERSION : MAX_DEFAULT_VERSION;
    }

    @JsonRpcMethod("server.version")
    public List<String> getServerVersion(@JsonRpcParam("client_name") String clientName, @JsonRpcParam("protocol_version") Object protocolVersion) throws UnsupportedVersionException {
        Version clientVersion = new Version(switch(protocolVersion) {
            case String s -> s;
            case List<?> versions -> {
                if(versions.isEmpty()) throw new IllegalArgumentException("protocol_version list cannot be empty");
                yield versions.size() > 1 ? versions.get(1).toString() : versions.get(0).toString();
            }
            case String[] versions -> {
                if(versions.length == 0) throw new IllegalArgumentException("protocol_version array cannot be empty");
                yield versions.length > 1 ? versions[1] : versions[0];
            }
            case null, default -> throw new IllegalArgumentException("Invalid protocol_version type: " + protocolVersion);
        });

        Version backendVersion = clientVersion;
        if(electrumBackendService != null) {
            List<String> backendVersions = electrumBackendService.getServerVersion(clientName, protocolVersion);
            if(backendVersions != null && !backendVersions.isEmpty()) {
                backendVersion = new Version(backendVersions.getLast());
            }
        }

        Version version = backendVersion.compareTo(clientVersion) < 0 ? backendVersion : clientVersion;
        if(version.compareTo(MIN_VERSION) < 0) {
            throw new UnsupportedVersionException(version.get());
        }

        this.protocolVersion = version.compareTo(getMaxSupportedVersion()) > 0 ? getMaxSupportedVersion() : version;
        return List.of(Frigate.SERVER_NAME + " " + Frigate.SERVER_VERSION, this.protocolVersion.get());
    }

    @JsonRpcMethod("server.banner")
    public String getServerBanner() {
        checkVersionNegotiated();
        return Frigate.SERVER_NAME + " " + Frigate.SERVER_VERSION + "\n" + bitcoindClient.getNetworkInfo().subversion() + (bitcoindClient.getNetworkInfo().networkactive() ? "" : " (disconnected)");
    }

    @JsonRpcMethod("server.features")
    public ServerFeatures getServerFeatures() {
        checkVersionNegotiated();
        if(electrumBackendService != null) {
            return electrumBackendService.getServerFeatures();
        }

        throw new UnsupportedOperationException("Configure backendElectrumServer to use server.features");
    }

    @JsonRpcMethod("server.add_peer")
    public boolean addPeer(@JsonRpcParam("features") ServerFeatures features) {
        checkVersionNegotiated();
        if(electrumBackendService != null) {
            return electrumBackendService.addPeer(features);
        }

        throw new UnsupportedOperationException("Configure backendElectrumServer to use server.add_peer");
    }

    @JsonRpcMethod("server.donation_address")
    public String getDonationAddress() {
        checkVersionNegotiated();
        if(electrumBackendService != null) {
            return electrumBackendService.getDonationAddress();
        }

        throw new UnsupportedOperationException("Configure backendElectrumServer to use server.donation_address");
    }

    @JsonRpcMethod("server.peers.subscribe")
    public List<ServerPeer> subscribePeers() {
        checkVersionNegotiated();
        if(electrumBackendService != null) {
            return electrumBackendService.subscribePeers();
        }

        throw new UnsupportedOperationException("Configure backendElectrumServer to use server.peers.subscribe");
    }

    @JsonRpcMethod("blockchain.estimatefee")
    public Double estimateFee(@JsonRpcParam("number") int blocks, @JsonRpcParam("mode") @JsonRpcOptional String mode) throws BitcoindIOException {
        checkVersionNegotiated();
        try {
            FeeInfo feeInfo = bitcoindClient.getBitcoindService().estimateSmartFee(blocks, mode);
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
        checkVersionNegotiated();
        if(electrumBackendService != null) {
            return electrumBackendService.getFeeHistogram();
        }

        throw new UnsupportedOperationException("Configure backendElectrumServer to use mempool.get_fee_histogram");
    }

    @JsonRpcMethod("blockchain.relayfee")
    public Double getRelayFee() throws BitcoindIOException {
        checkVersionNegotiated();
        try {
            MempoolInfo mempoolInfo = bitcoindClient.getBitcoindService().getMempoolInfo();
            return mempoolInfo.minrelaytxfee();
        } catch(IllegalStateException e) {
            throw new BitcoindIOException(e);
        }
    }

    @JsonRpcMethod("mempool.get_info")
    public MempoolInfo getMempoolInfo() throws BitcoindIOException {
        checkVersionNegotiated();
        try {
            return bitcoindClient.getBitcoindService().getMempoolInfo();
        } catch(IllegalStateException e) {
            throw new BitcoindIOException(e);
        }
    }

    @JsonRpcMethod("blockchain.headers.subscribe")
    public ElectrumBlockHeader subscribeHeaders() {
        checkVersionNegotiated();
        requestHandler.setHeadersSubscribed(true);
        return bitcoindClient.getTip();
    }

    @JsonRpcMethod("server.ping")
    public Object ping() throws BitcoindIOException {
        checkVersionNegotiated();
        try {
            bitcoindClient.getBitcoindService().uptime();
            return null;
        } catch(IllegalStateException e) {
            throw new BitcoindIOException(e);
        }
    }

    @JsonRpcMethod("blockchain.scripthash.subscribe")
    public String subscribeScriptHash(@JsonRpcParam("scripthash") String scriptHash) {
        checkVersionNegotiated();
        if(electrumBackendService != null) {
            String status = electrumBackendService.subscribeScriptHash(scriptHash);
            requestHandler.subscribeScriptHash(scriptHash);
            return status;
        }

        throw new UnsupportedOperationException("Configure backendElectrumServer to use blockchain.scripthash.subscribe");
    }

    @JsonRpcMethod("blockchain.scripthash.unsubscribe")
    public String unsubscribeScriptHash(@JsonRpcParam("scripthash") String scriptHash) {
        checkVersionNegotiated();
        if(electrumBackendService != null) {
            String status = electrumBackendService.unsubscribeScriptHash(scriptHash);
            requestHandler.unsubscribeScriptHash(scriptHash);
            return status;
        }

        throw new UnsupportedOperationException("Configure backendElectrumServer to use blockchain.scripthash.unsubscribe");
    }

    @JsonRpcMethod("blockchain.scripthash.get_balance")
    public ScriptHashBalance getBalance(@JsonRpcParam("scripthash") String scriptHash) {
        checkVersionNegotiated();
        if(electrumBackendService != null) {
            return electrumBackendService.getBalance(scriptHash);
        }

        throw new UnsupportedOperationException("Configure backendElectrumServer to use blockchain.scripthash.get_balance");
    }

    @JsonRpcMethod("blockchain.scripthash.get_history")
    public Collection<TxEntry> getHistory(@JsonRpcParam("scripthash") String scriptHash) {
        checkVersionNegotiated();
        if(electrumBackendService != null) {
            return electrumBackendService.getHistory(scriptHash);
        }

        throw new UnsupportedOperationException("Configure backendElectrumServer to use blockchain.scripthash.get_history");
    }

    @JsonRpcMethod("blockchain.scripthash.get_mempool")
    public Collection<TxEntry> getMempool(@JsonRpcParam("scripthash") String scriptHash) {
        checkVersionNegotiated();
        if(electrumBackendService != null) {
            return electrumBackendService.getMempool(scriptHash);
        }

        throw new UnsupportedOperationException("Configure backendElectrumServer to use blockchain.scripthash.get_mempool");
    }

    @JsonRpcMethod("blockchain.scripthash.listunspent")
    public Collection<UnspentOutput> listUnspent(@JsonRpcParam("scripthash") String scriptHash) {
        checkVersionNegotiated();
        if(electrumBackendService != null) {
            return electrumBackendService.listUnspent(scriptHash);
        }

        throw new UnsupportedOperationException("Configure backendElectrumServer to use blockchain.scripthash.listunspent");
    }

    @JsonRpcMethod("blockchain.block.header")
    public Object getBlockHeader(@JsonRpcParam("height") int height, @JsonRpcParam("cp_height") @JsonRpcOptional Integer cpHeight) throws BitcoindIOException, BlockNotFoundException {
        checkVersionNegotiated();
        if(cpHeight != null && cpHeight > 0) {
            if(electrumBackendService != null) {
                return electrumBackendService.getBlockHeader(height, cpHeight);
            }
            throw new UnsupportedOperationException("Configure backendElectrumServer to use cp_height");
        }

        try {
            String blockHash = bitcoindClient.getBitcoindService().getBlockHash(height);
            return bitcoindClient.getBitcoindService().getBlockHeader(blockHash, false);
        } catch(JsonRpcException e) {
            throw new BlockNotFoundException(e.getErrorMessage());
        } catch(IllegalStateException e) {
            throw new BitcoindIOException(e);
        }
    }

    @JsonRpcMethod("blockchain.block.headers")
    public Object getBlockHeaders(@JsonRpcParam("start_height") int startHeight, @JsonRpcParam("count") int count, @JsonRpcParam("cp_height") @JsonRpcOptional Integer cpHeight) {
        checkVersionNegotiated();
        if(electrumBackendService != null) {
            if(cpHeight != null && cpHeight > 0) {
                return electrumBackendService.getBlockHeaders(startHeight, count, cpHeight);
            } else {
                return electrumBackendService.getBlockHeaders(startHeight, count);
            }
        }

        throw new UnsupportedOperationException("Configure backendElectrumServer to use blockchain.block.headers");
    }

    @JsonRpcMethod("blockchain.block.stats")
    public BlockStats getBlockStats(@JsonRpcParam("height") int height) throws BitcoindIOException, BlockNotFoundException {
        checkVersionNegotiated();
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
        checkVersionNegotiated();
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
                    throw new BitcoindIOException(ex);
                }
            } catch(IllegalStateException e) {
                throw new BitcoindIOException(e);
            }
        }
    }

    @JsonRpcMethod("blockchain.transaction.get_merkle")
    public TransactionMerkle getTransactionMerkle(@JsonRpcParam("tx_hash") String txHash, @JsonRpcParam("height") int height) {
        checkVersionNegotiated();
        if(electrumBackendService != null) {
            return electrumBackendService.getTransactionMerkle(txHash, height);
        }

        throw new UnsupportedOperationException("Configure backendElectrumServer to use blockchain.transaction.get_merkle");
    }

    @JsonRpcMethod("blockchain.transaction.id_from_pos")
    public Object getTransactionIdFromPos(@JsonRpcParam("height") int height, @JsonRpcParam("tx_pos") int txPos, @JsonRpcParam("merkle") @JsonRpcOptional Boolean merkle) {
        checkVersionNegotiated();
        if(electrumBackendService != null) {
            return electrumBackendService.getTransactionIdFromPos(height, txPos, merkle);
        }

        throw new UnsupportedOperationException("Configure backendElectrumServer to use blockchain.transaction.id_from_pos");
    }

    @JsonRpcMethod("blockchain.transaction.broadcast")
    public String broadcastTransaction(@JsonRpcParam("raw_tx") String rawTx) throws BitcoindIOException, BroadcastFailedException {
        checkVersionNegotiated();
        try {
            return bitcoindClient.getBitcoindService().sendRawTransaction(rawTx, 0d);
        } catch(JsonRpcException e) {
            throw new BroadcastFailedException(e.getErrorMessage());
        } catch(IllegalStateException e) {
            throw new BitcoindIOException(e);
        }
    }

    @JsonRpcMethod("blockchain.transaction.broadcast_package")
    public Object broadcastTransactionPackage(@JsonRpcParam("raw_txs") String[] rawTxes, @JsonRpcParam("verbose") @JsonRpcOptional Boolean verbose) throws BitcoindIOException, BroadcastFailedException {
        checkVersionNegotiated();
        try {
            if(verbose == null || !verbose) {
                PackageResult result = bitcoindClient.getBitcoindService().submitPackage(rawTxes, null, null);
                return PackageResultSummary.fromPackageResult(result);
            } else {
                return bitcoindClient.getBitcoindService().submitPackage(rawTxes, null, null);
            }
        } catch(JsonRpcException e) {
            throw new BroadcastFailedException(e.getErrorMessage());
        } catch(IllegalStateException e) {
            throw new BitcoindIOException(e);
        }
    }

    @JsonRpcMethod("blockchain.silentpayments.subscribe")
    public String subscribeSilentPayments(@JsonRpcParam("scan_private_key") String scanPrivateKey, @JsonRpcParam("spend_public_key") String spendPublicKey, @JsonRpcParam("start") @JsonRpcOptional Long start, @JsonRpcParam("labels") @JsonRpcOptional Integer[] labels) {
        checkVersionNegotiated();
        SilentPaymentScanAddress silentPaymentScanAddress = getSilentPaymentScanAddress(scanPrivateKey, spendPublicKey);
        Set<Integer> labelSet = getLabels(labels);
        requestHandler.subscribeSilentPaymentsAddress(silentPaymentScanAddress, labelSet);

        int startHeight = getStartHeight(start);
        indexQuerier.startHistoryScan(silentPaymentScanAddress, startHeight, null, labelSet, new WeakReference<>(requestHandler));

        return silentPaymentScanAddress.getAddress();
    }

    @JsonRpcMethod("blockchain.silentpayments.unsubscribe")
    public String unsubscribeSilentPayments(@JsonRpcParam("scan_private_key") String scanPrivateKey, @JsonRpcParam("spend_public_key") String spendPublicKey) {
        checkVersionNegotiated();
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

    private Set<Integer> getLabels(Integer[] labels) {
        Set<Integer> labelSet = new HashSet<>();
        labelSet.add(0);
        if(labels != null) {
            labelSet.addAll(Arrays.stream(labels).filter(Objects::nonNull).filter(integer -> integer.compareTo(0) > 0).collect(Collectors.toSet()));
        }
        return Collections.unmodifiableSet(labelSet);
    }
}
