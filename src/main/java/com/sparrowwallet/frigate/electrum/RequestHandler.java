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
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class RequestHandler implements Runnable, SubscriptionStatus, HeadersDispatcher.Subscriber, Thread.UncaughtExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(RequestHandler.class);
    private final Socket clientSocket;
    private final ElectrumServerService electrumServerService;
    private final JsonRpcServer rpcServer = new JsonRpcServer();
    private final AtomicBoolean disconnected = new AtomicBoolean(false);
    private final ElectrumTransport backendTransport;
    private volatile Thread reader;
    private final HeadersDispatcher headersDispatcher;

    private boolean connected;
    private volatile boolean headersSubscribed;
    private final ScriptHashSubscriptions scriptHashSubscriptions = new ScriptHashSubscriptions();
    private final Map<String, SilentPaymentAddressSubscription> silentPaymentsAddressesSubscribed = new ConcurrentHashMap<>();
    private final Deque<Runnable> postResponseTasks = new ArrayDeque<>();
    private final Object silentPaymentsNotificationLock = new Object();

    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile PrintWriter out;
    private final ElectrumNotificationService notificationService;
    private final AsyncNotifier notifier;

    public RequestHandler(Socket clientSocket, BitcoindClient bitcoindClient, IndexQuerier indexQuerier) {
        this.clientSocket = clientSocket;
        this.headersDispatcher = bitcoindClient != null ? bitcoindClient.getHeadersDispatcher() : null;
        Config.ServerConfig serverConfig = Config.get().getServer();
        Server backendServer = serverConfig.getBackendElectrumServerObj();
        if(backendServer != null) {
            long requestTimeoutMillis = TimeUnit.SECONDS.toMillis(serverConfig.getBackendRequestTimeoutSeconds());
            this.backendTransport = new ElectrumTransport(backendServer.getHostAndPort(), backendServer.getProtocol(),
                    new BackendSubscriptionService(scriptHashSubscriptions, this::notifyScriptHash), requestTimeoutMillis);
        } else {
            this.backendTransport = null;
        }
        this.electrumServerService = new ElectrumServerService(bitcoindClient, this, indexQuerier, backendTransport);
        this.notificationService = new JsonRpcClient(new ElectrumNotificationTransport(this)).onDemand(ElectrumNotificationService.class);
        this.notifier = new AsyncNotifier("ElectrumNotify-" + System.identityHashCode(this), AsyncNotifier.DEFAULT_QUEUE_SIZE, scriptHashSubscriptions,
                notificationService::notifyScriptHash, this::disconnectSlowConsumer);
    }

    public void run() {
        Frigate.getEventBus().register(this);
        this.connected = true;
        notifier.start();

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

                try {
                    String response = rpcServer.handle(request, electrumServerService);
                    writeLine(response);
                } finally {
                    //statuses held for subscribes in this request can follow the response now it has been written
                    notifier.releaseHeld();
                }

                runPostResponseTasks();
            }
        } catch(IOException e) {
            log.debug("Could not communicate with client socket: {}", e.getMessage());
        } finally {
            notifier.close();
            closeBackendTransport();
            this.connected = false;
            this.disconnected.set(true);
            Frigate.getEventBus().unregister(this);
            if(headersDispatcher != null) {
                headersDispatcher.unsubscribe(this);
            }

            closeClientSocket(notifier.isDelivering());
        }
    }

    /**
     * An abortive close is needed when the notifier may be blocked writing to a client that has stopped reading: an SSL socket's
     * close() first sends close_notify, which waits without limit for the write lock the blocked writer holds. With SO_LINGER
     * set to zero the SSL socket only tries that lock, shuts the connection down directly, and the blocked write fails.
     * It is not used for a normal close, where a TCP reset could discard a final response the client has not yet received.
     */
    private void closeClientSocket(boolean abortive) {
        if(abortive) {
            try {
                clientSocket.setSoLinger(true, 0);
            } catch(SocketException e) {
                //the disconnect thread and the request thread's finally block can race to close the socket
                log.debug("Could not set SO_LINGER on client socket, already closed: {}", e.getMessage());
            }
        }

        try {
            clientSocket.close();
        } catch(IOException e) {
            log.error("Error closing client socket", e);
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
        if(backendTransport == null) {
            return;
        }

        try {
            backendTransport.connect();
        } catch(IOException e) {
            //proxied requests fail with BackendUnavailableException while the transport is not connected
            log.error(e.getMessage());
            return;
        }

        //each connection is read by its own thread, as a finished thread cannot be restarted
        Thread newReader = Thread.ofVirtual().name("BackendServerReadThread-" + System.identityHashCode(this)).unstarted(this::readBackend);
        newReader.setUncaughtExceptionHandler(this);
        reader = newReader;
        newReader.start();
    }

    private void closeBackendTransport() {
        if(backendTransport != null) {
            try {
                backendTransport.close();
            } catch(IOException e) {
                log.error("Error closing transport", e);
            }
        }

        Thread current = reader;
        if(current != null && current.isAlive()) {
            current.interrupt();
        }
    }

    @Override
    public boolean isConnected() {
        return !disconnected.get() || connected;
    }

    public void subscribeHeaders() {
        this.headersSubscribed = true;
        if(headersDispatcher != null) {
            headersDispatcher.subscribe(this);
        }
    }

    @Override
    public boolean isHeadersSubscribed() {
        return headersSubscribed;
    }

    /**
     * Called before the backend subscribe request is sent. Statuses for the scripthash are held back from the client until the
     * response has been written, see AsyncNotifier.
     */
    public void subscribeScriptHash(String scriptHash) {
        scriptHashSubscriptions.subscribe(scriptHash);
        notifier.hold(scriptHash);
    }

    /**
     * @param sequence the backend read sequence of the subscribe response, see ElectrumTransport.getReadSequence()
     */
    public void recordScriptHashSubscribeResponse(String scriptHash, String status, long sequence) {
        scriptHashSubscriptions.recordSubscribeResponse(scriptHash, status, sequence);
        notifier.discardScriptHash(scriptHash, sequence);
    }

    public void unsubscribeScriptHash(String scriptHash) {
        scriptHashSubscriptions.unsubscribe(scriptHash);
        notifier.discardScriptHash(scriptHash);
    }

    @Override
    public boolean isScriptHashSubscribed(String scriptHash) {
        return scriptHashSubscriptions.isSubscribed(scriptHash);
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

    @Override
    public void notifyHeaders(ElectrumBlockHeader electrumBlockHeader) {
        notifier.notify(() -> notificationService.notifyHeaders(electrumBlockHeader));
    }

    void notifyScriptHash(String scriptHash, String status, long sequence) {
        notifier.notifyScriptHash(scriptHash, status, sequence);
    }

    /**
     * Called on whichever thread produced the overflowing notification (the headers dispatcher, a scan, or the backend reader),
     * so the close runs on its own thread and can never block the producer. Closing the socket unblocks the request thread's
     * read, whose finally block then tears down the session.
     */
    private void disconnectSlowConsumer() {
        Thread.ofVirtual().name("ElectrumDisconnect-" + System.identityHashCode(this)).start(() -> closeClientSocket(true));
    }

    /**
     * Called directly by the scan that this session requested (no EventBus fan-out). Scans run on several threads, so calls are
     * serialized here to keep the mempool txid bookkeeping and the delivery filter atomic, as the EventBus previously guaranteed.
     * The bookkeeping happens here; only the write to the client is deferred to the notifier, queued under the lock to keep order.
     *
     * Because the write is deferred, a client that reads slowly no longer throttles the scan: previously the scan thread blocked
     * in writeLine, so a slow client simply received results slowly. Now notifications queue in the notifier, each holding its
     * deliverable list in memory until sent, and a client that falls queueSize notifications behind is disconnected. Historical
     * scans deliver pages of HISTORY_PAGE_SIZE (100) entries every 5 seconds, so this needs around 100k results in one poll
     * interval, or a very slow link (such as Tor) during a long scan. Applying backpressure to the scan instead of disconnecting
     * would restore the old behaviour for such clients.
     */
    @Override
    public void notifySilentPayments(SilentPaymentsNotification notification) {
        if(!isConnected()) {
            return;
        }

        synchronized(silentPaymentsNotificationLock) {
            SilentPaymentAddressSubscription subscription = silentPaymentsAddressesSubscribed.get(notification.subscription().address());
            if(subscription == null || !subscription.isActive()) {
                return;
            }
            notification.history().stream().mapToInt(SilentPaymentsTxEntry::getHeight).filter(h -> h > 0).max().ifPresent(subscription::accumulateMaxBlockHeight);
            subscription.getMempoolTxids().addAll(notification.history().stream().filter(txEntry -> txEntry.height <= 0).map(txEntry -> Sha256Hash.wrap(txEntry.tx_hash)).collect(Collectors.toSet()));

            List<SilentPaymentsTxEntry> deliverable = notification.history().stream()
                    .filter(txEntry -> txEntry.height <= 0 || !subscription.getMempoolTxids().contains(Sha256Hash.wrap(txEntry.tx_hash))).toList();

            notifier.notify(() -> notificationService.notifySilentPayments(notification.subscription(), notification.progress(), deliverable));
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

    /**
     * Reads the backend connection until it ends. If it was lost (including being closed by a request timeout) rather than
     * closed with the session, the client is disconnected: its subscriptions live on the lost connection, and a disconnected
     * wallet reconnects and resubscribes, whereas one left connected would silently stop receiving notifications.
     */
    private void readBackend() {
        backendTransport.readInputLoop();

        if(!backendTransport.isClosed()) {
            Exception cause = backendTransport.getLastException();
            log.warn("Connection to backend Electrum server lost" + (cause == null ? "" : " (" + cause.getMessage() + ")") + ", disconnecting client " + clientSocket.getRemoteSocketAddress());
            //abortive, as the notifier is still open and may start a write to a client that is not reading at any moment
            //(isDelivering() is only reliable after the notifier is closed); unsent data does not matter when disconnecting
            closeClientSocket(true);
        }
    }
}
