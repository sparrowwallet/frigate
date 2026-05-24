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
import com.sparrowwallet.frigate.io.Config;
import com.sparrowwallet.frigate.io.Protocol;
import com.sparrowwallet.frigate.io.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.*;

@JsonRpcService
public class ElectrumServerService {
    private static final Logger log = LoggerFactory.getLogger(ElectrumServerService.class);
    public static final Version MIN_VERSION = new Version("1.4");
    public static final Version MAX_DEFAULT_VERSION = new Version("1.4.2");
    public static final Version MAX_SUBMIT_PACKAGE_VERSION = new Version("1.6");
    public static final List<Integer> SILENT_PAYMENTS_SUPPORTED_VERSIONS = List.of(0);
    private static final int METHOD_NOT_FOUND = -32601;

    private final BitcoindClient bitcoindClient;
    private final RequestHandler requestHandler;
    private final IndexQuerier indexQuerier;
    private final ElectrumBackendService electrumBackendService;
    private Version protocolVersion;
    private String genesisHash;

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
        return Frigate.SERVER_NAME + " " + Frigate.SERVER_VERSION + (bitcoindClient != null ? "\n" + bitcoindClient.getNetworkInfo().subversion() + (bitcoindClient.getNetworkInfo().networkactive() ? "" : " (disconnected)") : "");
    }

    @JsonRpcMethod("server.features")
    public ServerFeatures getServerFeatures() {
        checkVersionNegotiated();
        Map<String, ServerFeatures.HostInfo> ourHosts = buildAdvertisedHosts(Config.get().getServer().getAdvertisedHosts());

        if(electrumBackendService != null) {
            try {
                return electrumBackendService.getServerFeatures().withHosts(ourHosts).withSilentPayments(SILENT_PAYMENTS_SUPPORTED_VERSIONS);
            } catch(JsonRpcException e) {
                if(e.getErrorMessage() == null || e.getErrorMessage().getCode() != METHOD_NOT_FOUND) {
                    throw e;
                }
                log.debug("Backend does not support server.features, returning local response");
            }
        }

        return new ServerFeatures(ourHosts, getGenesisHash(), "sha256", Frigate.SERVER_NAME + " " + Frigate.SERVER_VERSION,
                protocolVersion.get(), MIN_VERSION.get(), null, SILENT_PAYMENTS_SUPPORTED_VERSIONS);
    }

    static Map<String, ServerFeatures.HostInfo> buildAdvertisedHosts(List<Server> servers) {
        Map<String, ServerFeatures.HostInfo> result = new LinkedHashMap<>();
        for(Server server : servers) {
            int port = server.getHostAndPort().getPort();
            ServerFeatures.HostInfo current = result.get(server.getHost());
            Integer tcp = current == null ? null : current.tcp_port();
            Integer ssl = current == null ? null : current.ssl_port();
            if(server.getProtocol() == Protocol.TCP) {
                tcp = port;
            } else if(server.getProtocol() == Protocol.SSL) {
                ssl = port;
            }
            result.put(server.getHost(), new ServerFeatures.HostInfo(tcp, ssl));
        }
        return result;
    }

    private String getGenesisHash() {
        if(genesisHash == null && bitcoindClient != null) {
            try {
                genesisHash = bitcoindClient.getBitcoindService().getBlockHash(0);
            } catch(Exception e) {
                log.debug("Could not fetch genesis hash from bitcoind", e);
            }
        }

        return genesisHash;
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
        if(bitcoindClient == null) {
            return MempoolInfo.DEFAULT_FEE_RATE;
        }

        try {
            FeeInfo feeInfo = bitcoindClient.getBitcoindService().estimateSmartFee(blocks, mode);
            if(feeInfo == null || feeInfo.feerate() == null) {
                return MempoolInfo.DEFAULT_FEE_RATE;
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
        if(bitcoindClient == null) {
            return MempoolInfo.DEFAULT_FEE_RATE;
        }

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
        if(bitcoindClient == null) {
            return MempoolInfo.DEFAULT;
        }

        try {
            return bitcoindClient.getBitcoindService().getMempoolInfo();
        } catch(IllegalStateException e) {
            throw new BitcoindIOException(e);
        }
    }

    @JsonRpcMethod("blockchain.headers.subscribe")
    public ElectrumBlockHeader subscribeHeaders() {
        checkVersionNegotiated();
        if(bitcoindClient == null) {
            throw new UnsupportedOperationException("Configure coreServer to use blockchain.headers.subscribe");
        }
        ElectrumBlockHeader tip = bitcoindClient.getTip();
        requestHandler.runAfterResponse(() -> {
            requestHandler.setHeadersSubscribed(true);
            ElectrumBlockHeader currentTip = bitcoindClient.getTip();
            if(currentTip != null && !currentTip.equals(tip)) {
                requestHandler.notifyHeaders(currentTip);
            }
        });
        return tip;
    }

    @JsonRpcMethod("server.ping")
    public Object ping() throws BitcoindIOException {
        checkVersionNegotiated();
        try {
            if(bitcoindClient != null) {
                bitcoindClient.getBitcoindService().uptime();
            }
            return null;
        } catch(IllegalStateException e) {
            throw new BitcoindIOException(e);
        }
    }

    @JsonRpcMethod("blockchain.scripthash.subscribe")
    public String subscribeScriptHash(@JsonRpcParam("scripthash") String scriptHash) {
        checkVersionNegotiated();
        if(electrumBackendService != null) {
            requestHandler.subscribeScriptHash(scriptHash);
            try {
                return electrumBackendService.subscribeScriptHash(scriptHash);
            } catch(RuntimeException e) {
                requestHandler.unsubscribeScriptHash(scriptHash);
                throw e;
            }
        }

        throw new UnsupportedOperationException("Configure backendElectrumServer to use blockchain.scripthash.subscribe");
    }

    @JsonRpcMethod("blockchain.scripthash.unsubscribe")
    public String unsubscribeScriptHash(@JsonRpcParam("scripthash") String scriptHash) {
        checkVersionNegotiated();
        if(electrumBackendService != null) {
            requestHandler.unsubscribeScriptHash(scriptHash);
            return electrumBackendService.unsubscribeScriptHash(scriptHash);
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

        if(bitcoindClient == null) {
            throw new UnsupportedOperationException("Configure coreServer to use blockchain.block.header");
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
        if(bitcoindClient == null) {
            throw new UnsupportedOperationException("Configure coreServer to use blockchain.block.stats");
        }

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
        if(bitcoindClient == null) {
            throw new UnsupportedOperationException("Configure coreServer to use blockchain.transaction.get");
        }

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
        if(bitcoindClient == null) {
            throw new UnsupportedOperationException("Configure coreServer to use blockchain.transaction.broadcast");
        }

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
        if(bitcoindClient == null) {
            throw new UnsupportedOperationException("Configure coreServer to use blockchain.transaction.broadcast_package");
        }

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
    public SilentPaymentsSubscription subscribeSilentPayments(@JsonRpcParam("scan_private_key") String scanPrivateKey, @JsonRpcParam("spend_public_key") String spendPublicKey, @JsonRpcParam("start") @JsonRpcOptional Object start, @JsonRpcParam("labels") @JsonRpcOptional Integer[] labels) throws InvalidParamsException {
        checkVersionNegotiated();
        SilentPaymentScanAddress silentPaymentScanAddress = parseScanAddress(scanPrivateKey, spendPublicKey);
        Set<Integer> labelSet = parseLabels(labels);

        int maxSubscriptions = Config.get().getScan().getMaxSubscriptions();
        if(!requestHandler.isSilentPaymentsAddressSubscribed(silentPaymentScanAddress.toString()) && requestHandler.getSilentPaymentsSubscriptionCount() >= maxSubscriptions) {
            throw new InvalidParamsException("subscription limit reached (" + maxSubscriptions + ") for this connection");
        }

        int[] heightRange = getHeightRange(start);
        int requestedStart = heightRange[0];
        Integer endHeight = heightRange.length > 1 ? heightRange[1] : null;

        int effectiveStart = requestedStart;
        SilentPaymentAddressSubscription existing = requestHandler.getSilentPaymentsAddressSubscription(silentPaymentScanAddress.toString());
        if(existing != null && existing.getStartHeight() < requestedStart) {
            effectiveStart = existing.getStartHeight();
        }

        int startHeight = effectiveStart;
        requestHandler.subscribeSilentPaymentsAddress(silentPaymentScanAddress, labelSet, startHeight);

        requestHandler.runAfterResponse(() -> {
            SilentPaymentAddressSubscription subscription = requestHandler.getSilentPaymentsAddressSubscription(silentPaymentScanAddress.toString());
            if(subscription == null) {
                return;
            }
            subscription.setActive(true);
            indexQuerier.startHistoryScan(silentPaymentScanAddress, startHeight, endHeight, subscription, new WeakReference<>(requestHandler), true);
        });

        return new SilentPaymentsSubscription(silentPaymentScanAddress.getAddress(), labelSet.toArray(new Integer[0]), startHeight);
    }

    @JsonRpcMethod("blockchain.silentpayments.unsubscribe")
    public String unsubscribeSilentPayments(@JsonRpcParam("scan_private_key") String scanPrivateKey, @JsonRpcParam("spend_public_key") String spendPublicKey) throws InvalidParamsException {
        checkVersionNegotiated();
        SilentPaymentScanAddress silentPaymentScanAddress = parseScanAddress(scanPrivateKey, spendPublicKey);
        requestHandler.unsubscribeSilentPaymentsAddress(silentPaymentScanAddress);

        return silentPaymentScanAddress.getAddress();
    }

    private static SilentPaymentScanAddress parseScanAddress(String scanPrivateKey, String spendPublicKey) throws InvalidParamsException {
        byte[] scanBytes;
        byte[] spendBytes;
        try {
            scanBytes = Utils.hexToBytes(scanPrivateKey);
            spendBytes = Utils.hexToBytes(spendPublicKey);
        } catch(IllegalArgumentException e) {
            throw new InvalidParamsException("scan_private_key or spend_public_key is not valid hex", e);
        }
        if(scanBytes.length != 32) {
            throw new InvalidParamsException("scan_private_key must be 32 bytes");
        }
        if(spendBytes.length != 33) {
            throw new InvalidParamsException("spend_public_key must be 33 bytes (compressed)");
        }
        try {
            ECKey scanKey = ECKey.fromPrivate(scanBytes);
            ECKey spendKey = ECKey.fromPublicOnly(spendBytes);
            return SilentPaymentScanAddress.from(scanKey, spendKey);
        } catch(IllegalArgumentException e) {
            throw new InvalidParamsException("invalid scan/spend key: " + e.getMessage(), e);
        }
    }

    private int[] getHeightRange(Object start) throws InvalidParamsException {
        if(start == null) {
            return new int[] { 0 };
        }

        if(start instanceof String s) {
            if(!s.contains("-")) {
                throw new InvalidParamsException("start string must be of the form 'FROM-TO'");
            }
            String[] parts = s.split("-", 2);
            int from;
            int to;
            try {
                from = Integer.parseInt(parts[0]);
                to = Integer.parseInt(parts[1]);
            } catch(NumberFormatException e) {
                throw new InvalidParamsException("start range must contain integer block heights", e);
            }
            if(from < 0 || to < from) {
                throw new InvalidParamsException("start range must satisfy 0 <= from <= to");
            }
            int tip = bitcoindClient != null && bitcoindClient.getTip() != null ? bitcoindClient.getTip().height() : Integer.MAX_VALUE;
            if(to > tip) {
                throw new InvalidParamsException("start range 'to' (" + to + ") exceeds tip (" + tip + ")");
            }
            return new int[] { from, to };
        }

        if(start instanceof Number n) {
            long startLong = n.longValue();
            if(startLong < 0) {
                throw new InvalidParamsException("start must be non-negative");
            }
            if(startLong > Transaction.MAX_BLOCK_LOCKTIME) {
                if(bitcoindClient == null) {
                    throw new InvalidParamsException("timestamp start requires coreServer to be configured");
                }
                return new int[] { bitcoindClient.findBlockByTimestamp(startLong) };
            }
            return new int[] { (int)startLong };
        }

        throw new InvalidParamsException("start must be an integer or 'FROM-TO' string");
    }

    private Set<Integer> parseLabels(Integer[] labels) throws InvalidParamsException {
        int maxLabels = Config.get().getScan().getMaxLabels();
        if(labels != null && labels.length > maxLabels) {
            throw new InvalidParamsException("labels array exceeds " + maxLabels + " entries");
        }

        SortedSet<Integer> labelSet = new TreeSet<>();
        labelSet.add(0);
        if(labels != null) {
            for(Integer label : labels) {
                if(label == null) {
                    continue;
                }
                if(label <= 0) {
                    throw new InvalidParamsException("label must satisfy 0 < label < 2^31, got " + label);
                }
                labelSet.add(label);
            }
        }
        return Collections.unmodifiableSortedSet(labelSet);
    }
}
