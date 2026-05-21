package com.sparrowwallet.frigate.electrum;

import com.github.arteam.simplejsonrpc.client.JsonRpcClient;
import com.github.arteam.simplejsonrpc.server.JsonRpcServer;
import com.google.common.eventbus.Subscribe;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.silentpayments.SilentPaymentScanAddress;
import com.sparrowwallet.frigate.Frigate;
import com.sparrowwallet.frigate.SubscriptionStatus;
import com.sparrowwallet.frigate.bitcoind.BitcoindClient;
import com.sparrowwallet.frigate.bitcoind.BlockReorgSyncStart;
import com.sparrowwallet.frigate.bitcoind.BlockReorgSyncComplete;
import com.sparrowwallet.frigate.index.*;
import com.sparrowwallet.frigate.io.Config;
import com.sparrowwallet.frigate.io.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.ref.WeakReference;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class RequestHandler implements Runnable, SubscriptionStatus, Thread.UncaughtExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(RequestHandler.class);
    private final Socket clientSocket;
    private final ElectrumServerService electrumServerService;
    private final JsonRpcServer rpcServer = new JsonRpcServer();
    private final AtomicBoolean disconnected = new AtomicBoolean(false);
    private final ElectrumTransport backendTransport;
    private final Thread reader;

    private boolean connected;
    private volatile boolean headersSubscribed;
    private final Set<String> scriptHashesSubscribed = ConcurrentHashMap.newKeySet();
    private final Map<String, SilentPaymentAddressSubscription> silentPaymentsAddressesSubscribed = new ConcurrentHashMap<>();
    private final Deque<Runnable> postResponseTasks = new ArrayDeque<>();

    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile PrintWriter out;
    private final ElectrumNotificationService notificationService;

    public RequestHandler(Socket clientSocket, BitcoindClient bitcoindClient, IndexQuerier indexQuerier) {
        this.clientSocket = clientSocket;
        Server backendServer = Config.get().getServer().getBackendElectrumServerObj();
        if(backendServer != null) {
            this.backendTransport = new ElectrumTransport(backendServer.getHostAndPort(), backendServer.getProtocol(), new BackendSubscriptionService());
            this.reader = Thread.ofVirtual().name("BackendServerReadThread-" + System.identityHashCode(this)).unstarted(new ReadRunnable(backendTransport));
            reader.setUncaughtExceptionHandler(this);
        } else {
            this.backendTransport = null;
            this.reader = null;
        }
        this.electrumServerService = new ElectrumServerService(bitcoindClient, this, indexQuerier, backendTransport);
        this.notificationService = new JsonRpcClient(new ElectrumNotificationTransport(this)).onDemand(ElectrumNotificationService.class);
    }

    public void run() {
        Frigate.getEventBus().register(this);
        this.connected = true;

        try {
            InputStream input = clientSocket.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));

            OutputStream output = clientSocket.getOutputStream();
            this.out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8)));

            connectBackendTransport();

            while(true) {
                postResponseTasks.clear();

                String request = reader.readLine();
                if(request == null) {
                    break;
                }

                // Skip requests with null bytes or other control characters
                if(request.indexOf(0) >= 0 || request.chars().anyMatch(c -> c < 32 && c != '\t' && c != '\r' && c != '\n')) {
                    log.warn("Skipping malformed request with control characters");
                    continue;
                }

                String response = rpcServer.handle(request, electrumServerService);
                writeLine(response);

                runPostResponseTasks();
            }
        } catch(IOException e) {
            log.debug("Could not communicate with client socket: {}", e.getMessage());
        } finally {
            closeBackendTransport();
            this.connected = false;
            this.disconnected.set(true);
            Frigate.getEventBus().unregister(this);

            try {
                clientSocket.close();
            } catch(IOException e) {
                log.error("Error closing client socket", e);
            }
        }
    }

    public void writeLine(String line) {
        writeLock.lock();
        try {
            PrintWriter writer = out;
            if(writer == null) {
                return;
            }
            writer.println(line);
            writer.flush();
        } finally {
            writeLock.unlock();
        }
    }

    private void connectBackendTransport() {
        if(backendTransport != null) {
            backendTransport.connect();
        }

        if(reader != null && !reader.isAlive()) {
            reader.start();
        }
    }

    private void closeBackendTransport() {
        if(backendTransport != null) {
            try {
                backendTransport.close();
            } catch(IOException e) {
                log.error("Error closing transport", e);
            }
        }

        if(reader != null && reader.isAlive()) {
            reader.interrupt();
        }
    }

    @Override
    public boolean isConnected() {
        return !disconnected.get() || connected;
    }

    public void setHeadersSubscribed(boolean headersSubscribed) {
        this.headersSubscribed = headersSubscribed;
    }

    @Override
    public boolean isHeadersSubscribed() {
        return headersSubscribed;
    }

    public void subscribeScriptHash(String scriptHash) {
        scriptHashesSubscribed.add(scriptHash);
    }

    public void unsubscribeScriptHash(String scriptHash) {
        scriptHashesSubscribed.remove(scriptHash);
    }

    @Override
    public boolean isScriptHashSubscribed(String scriptHash) {
        return scriptHashesSubscribed.contains(scriptHash);
    }

    public void subscribeSilentPaymentsAddress(SilentPaymentScanAddress silentPaymentsScanAddress, Set<Integer> labelSet, int startHeight) {
        SilentPaymentAddressSubscription previous = silentPaymentsAddressesSubscribed.get(silentPaymentsScanAddress.toString());
        if(previous != null) {
            previous.invalidateInFlightScans();
        }
        silentPaymentsAddressesSubscribed.put(silentPaymentsScanAddress.toString(), new SilentPaymentAddressSubscription(silentPaymentsScanAddress, labelSet, startHeight));
    }

    public void unsubscribeSilentPaymentsAddress(SilentPaymentScanAddress silentPaymentsScanAddress) {
        SilentPaymentAddressSubscription previous = silentPaymentsAddressesSubscribed.remove(silentPaymentsScanAddress.toString());
        if(previous != null) {
            previous.invalidateInFlightScans();
        }
    }

    public SilentPaymentAddressSubscription getSilentPaymentsAddressSubscription(String silentPaymentsAddress) {
        return silentPaymentsAddressesSubscribed.get(silentPaymentsAddress);
    }

    public void runAfterResponse(Runnable task) {
        postResponseTasks.add(task);
    }

    private void runPostResponseTasks() {
        while(!postResponseTasks.isEmpty()) {
            Runnable task = postResponseTasks.poll();
            try {
                task.run();
            } catch(Exception e) {
                log.error("Error running post-response task", e);
            }
        }
    }

    public int getSilentPaymentsSubscriptionCount() {
        return silentPaymentsAddressesSubscribed.size();
    }

    @Override
    public boolean isSilentPaymentsAddressSubscribed(String silentPaymentsAddress) {
        return silentPaymentsAddressesSubscribed.containsKey(silentPaymentsAddress);
    }

    @Override
    public Set<Sha256Hash> getSilentPaymentsMempoolTxids(String silentPaymentsAddress) {
        SilentPaymentAddressSubscription subscription = silentPaymentsAddressesSubscribed.get(silentPaymentsAddress);
        return subscription == null ? new HashSet<>() : subscription.getMempoolTxids();
    }

    @Subscribe
    public void newBlock(ElectrumBlockHeader electrumBlockHeader) {
        if(isHeadersSubscribed()) {
            notifyHeaders(electrumBlockHeader);
        }
    }

    void notifyHeaders(ElectrumBlockHeader electrumBlockHeader) {
        notificationService.notifyHeaders(electrumBlockHeader);
    }

    @Subscribe
    public void scriptHashStatus(ScriptHashStatus scriptHashStatus) {
        if(isScriptHashSubscribed(scriptHashStatus.scriptHash())) {
            notificationService.notifyScriptHash(scriptHashStatus.scriptHash(), scriptHashStatus.status());
        }
    }

    @Subscribe
    public void silentPaymentsNotification(SilentPaymentsNotification notification) {
        if(isSilentPaymentsAddressSubscribed(notification.subscription().address()) && notification.status() == this) {
            SilentPaymentAddressSubscription subscription = silentPaymentsAddressesSubscribed.get(notification.subscription().address());
            if(!subscription.isActive()) {
                return;
            }
            notification.history().stream().mapToInt(SilentPaymentsTxEntry::getHeight).filter(h -> h > 0).max().ifPresent(subscription::accumulateMaxBlockHeight);
            subscription.getMempoolTxids().addAll(notification.history().stream().filter(txEntry -> txEntry.height <= 0).map(txEntry -> Sha256Hash.wrap(txEntry.tx_hash)).collect(Collectors.toSet()));

            List<SilentPaymentsTxEntry> deliverable = notification.history().stream()
                    .filter(txEntry -> txEntry.height <= 0 || !subscription.getMempoolTxids().contains(Sha256Hash.wrap(txEntry.tx_hash))).toList();

            notificationService.notifySilentPayments(notification.subscription(), notification.progress(), deliverable);
        }
    }

    @Subscribe
    public void silentPaymentsBlocksIndexUpdate(SilentPaymentsBlocksIndexUpdate update) {
        for(SilentPaymentAddressSubscription subscription : silentPaymentsAddressesSubscribed.values()) {
            if(subscription.isActive() && !subscription.isPendingHistoricalRescan() && update.fromBlockHeight() > subscription.getHighestBlockHeight()) {
                electrumServerService.getIndexQuerier().startHistoryScan(subscription.getAddress(), update.fromBlockHeight(), null, subscription, new WeakReference<>(this), false);
            }
        }
    }

    @Subscribe
    public void silentPaymentsMempoolIndexAdded(SilentPaymentsMempoolIndexAdded added) {
        for(SilentPaymentAddressSubscription subscription : silentPaymentsAddressesSubscribed.values()) {
            if(subscription.isActive()) {
                electrumServerService.getIndexQuerier().startMempoolScan(subscription.getAddress(), null, null, added.getTxids(), subscription, new WeakReference<>(this));
            }
        }
    }

    @Subscribe
    public void blockReorgSyncStart(BlockReorgSyncStart event) {
        int reorgPoint = event.reorgStartHeight() - 1;
        for(SilentPaymentAddressSubscription subscription : silentPaymentsAddressesSubscribed.values()) {
            subscription.invalidateInFlightScans();
            subscription.accumulateMinBlockHeight(reorgPoint);
            if(subscription.isActive() && !subscription.isHistoricalComplete()) {
                subscription.markPendingHistoricalRescan();
            }
        }
    }

    @Subscribe
    public void blockReorgSyncComplete(BlockReorgSyncComplete event) {
        for(SilentPaymentAddressSubscription subscription : silentPaymentsAddressesSubscribed.values()) {
            if(subscription.isActive() && subscription.consumePendingHistoricalRescan()) {
                int scanFrom = subscription.getHighestBlockHeight() + 1;
                electrumServerService.getIndexQuerier().startHistoryScan(subscription.getAddress(), scanFrom, null, subscription, new WeakReference<>(this), true);
            }
        }
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        log.error("Uncaught exception in thread " + t.getName(), e);
    }

    public static class ReadRunnable implements Runnable {
        private final ElectrumTransport electrumTransport;

        public ReadRunnable(ElectrumTransport electrumTransport) {
            this.electrumTransport = electrumTransport;
        }

        @Override
        public void run() {
            try {
                electrumTransport.readInputLoop();

                if(electrumTransport.getLastException() != null && !electrumTransport.isClosed()) {
                    log.error("Connection to Electrum server lost", electrumTransport.getLastException());
                }
            } catch(Exception e) {
                log.debug("Read thread terminated", e);
            }
        }
    }
}
