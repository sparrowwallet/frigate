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
import com.sparrowwallet.frigate.io.BackendTls;
import com.sparrowwallet.frigate.io.BoundedLineReader;
import com.sparrowwallet.frigate.io.Config;
import com.sparrowwallet.frigate.io.JsonRpcBatch;
import com.sparrowwallet.frigate.io.LineTooLongException;
import com.sparrowwallet.frigate.io.TokenBucket;
import com.sparrowwallet.frigate.io.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.ref.WeakReference;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
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

public class RequestHandler implements Runnable, SubscriptionStatus, HeadersDispatcher.Subscriber {
    private static final Logger log = LoggerFactory.getLogger(RequestHandler.class);
    private static final String REQUEST_TOO_LARGE = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"request too large\"},\"id\":null}";
    //bounds on draining the rest of an oversized request so its error is not lost to a reset: the deadline bounds the time a
    //client that keeps its connection open can hold the session, and the budget the data read from one that keeps sending
    private static final long OVERSIZED_REQUEST_DRAIN_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long OVERSIZED_REQUEST_DRAIN_BYTES = 16 * 1024 * 1024;
    private static final String BATCH_TOO_LARGE = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"batch too large\"},\"id\":null}";
    private static final String PARSE_ERROR = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700,\"message\":\"Parse error\"},\"id\":null}";
    private final Socket clientSocket;
    private final ElectrumServerService electrumServerService;
    private final JsonRpcServer rpcServer = new JsonRpcServer();
    private final AtomicBoolean disconnected = new AtomicBoolean(false);
    private final BackendSession backendSession;
    private final ConnectionGate connectionGate;
    private final ConnectionGate.IpKey ipKey;
    private final TokenBucket silentPaymentsSubscribeBucket;
    private final Object shutdownLock = new Object();
    private boolean shuttingDown;
    private boolean handlingRequest;
    private volatile Thread requestThread;
    private final HeadersDispatcher headersDispatcher;

    private boolean connected;
    private volatile boolean headersSubscribed;
    private final ScriptHashSubscriptions scriptHashSubscriptions = new ScriptHashSubscriptions();
    private final Map<String, SilentPaymentAddressSubscription> silentPaymentsAddressesSubscribed = new ConcurrentHashMap<>();
    private final Deque<Runnable> postResponseTasks = new ArrayDeque<>();
    private final List<String> heldForResponse = new ArrayList<>();
    private final Object silentPaymentsNotificationLock = new Object();

    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile PrintWriter out;
    private final ElectrumNotificationService notificationService;
    private final AsyncNotifier notifier;

    /**
     * @param connectionGate the server's gate, where this session reserves its scripthash subscriptions against the per-IP and global caps
     * @param ipKey the client's key in the connection gate
     * @param backendTls how the backend's certificate is authenticated, if it is ssl://
     */
    public RequestHandler(Socket clientSocket, BitcoindClient bitcoindClient, IndexQuerier indexQuerier, ConnectionGate connectionGate, ConnectionGate.IpKey ipKey,
                          BackendTls backendTls) {
        this.clientSocket = clientSocket;
        this.connectionGate = connectionGate;
        this.ipKey = ipKey;
        Config.LimitsConfig limits = Config.get().getLimits();
        this.silentPaymentsSubscribeBucket = new TokenBucket(limits.getSilentPaymentsSubscribeBurst(), 1.0 / limits.getSilentPaymentsSubscribeIntervalSeconds());
        this.headersDispatcher = bitcoindClient != null ? bitcoindClient.getHeadersDispatcher() : null;
        Config.ServerConfig serverConfig = Config.get().getServer();
        Server backendServer = serverConfig.getBackendElectrumServerObj();
        ElectrumTransport backendTransport = null;
        if(backendServer != null) {
            long requestTimeoutMillis = TimeUnit.SECONDS.toMillis(serverConfig.getBackendRequestTimeoutSeconds());
            backendTransport = new ElectrumTransport(backendServer.getHostAndPort(), backendServer.getProtocol(),
                    new BackendSubscriptionService(scriptHashSubscriptions, this::notifyScriptHash), requestTimeoutMillis, backendTls);
        }
        this.electrumServerService = new ElectrumServerService(bitcoindClient, this, indexQuerier, backendTransport);
        this.notificationService = new JsonRpcClient(new ElectrumNotificationTransport(this)).onDemand(ElectrumNotificationService.class);
        this.notifier = new AsyncNotifier("ElectrumNotify-" + System.identityHashCode(this), Config.get().getLimits().getNotificationQueueSize(), scriptHashSubscriptions,
                notificationService::notifyScriptHash, this::disconnectSlowConsumer);
        if(backendTransport != null) {
            BackendSession.Settings settings = new BackendSession.Settings(BackendSession.INITIAL_BACKOFF_MILLIS,
                    TimeUnit.SECONDS.toMillis(serverConfig.getBackendReconnectMaxBackoffSeconds()), TimeUnit.SECONDS.toMillis(serverConfig.getBackendPingIntervalSeconds()));
            this.backendSession = new BackendSession("BackendSession-" + System.identityHashCode(this), backendTransport, scriptHashSubscriptions, notifier,
                    electrumServerService::getBackendVersionRequest, settings);
        } else {
            this.backendSession = null;
        }
    }

    public void run() {
        requestThread = Thread.currentThread();
        Frigate.getEventBus().register(this);
        this.connected = true;
        notifier.start();

        try {
            Config.LimitsConfig limits = Config.get().getLimits();
            BoundedLineReader reader = new BoundedLineReader(clientSocket.getInputStream(), limits.getMaxRequestBytes());
            int maxBatchSize = limits.getMaxBatchSize();
            //idle timeout: Electrum clients ping about every minute, so a session silent for this long is dead or abandoned
            clientSocket.setSoTimeout((int)Math.min(TimeUnit.SECONDS.toMillis(limits.getSessionTimeoutSeconds()), Integer.MAX_VALUE));

            OutputStream output = clientSocket.getOutputStream();
            this.out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8)));

            //a session that began shutting down while starting needs no backend connection
            if(!isShuttingDown()) {
                startBackendSession();
            }

            while(!isShuttingDown()) {
                postResponseTasks.clear();

                String request;
                try {
                    request = reader.readLine();
                } catch(SocketTimeoutException e) {
                    log.debug("Closing idle session for client " + clientSocket.getRemoteSocketAddress());
                    break;
                } catch(LineTooLongException e) {
                    //recovery inside an oversized line is not meaningfully possible, so report it and disconnect
                    log.warn("Disconnecting client " + clientSocket.getRemoteSocketAddress() + ": request exceeds " + e.getMaxLineBytes() + " bytes");
                    rejectOversizedRequest();
                    break;
                }
                if(request == null || !startHandling()) {
                    //a request read as shutdown began is dropped, as if the connection had closed a moment earlier
                    break;
                }

                try {
                    handleRequest(request, maxBatchSize);
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } finally {
                    finishHandling();
                }
            }
        } catch(IOException e) {
            log.debug("Could not communicate with client socket: {}", e.getMessage());
        } finally {
            notifier.close();
            if(backendSession != null) {
                backendSession.close();
            }
            connectionGate.releaseSubscriptions(ipKey, scriptHashSubscriptions.unsubscribeAll());
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
     * Handles one request line: rejects it if it is malformed or an oversized batch, and otherwise paces it and writes its response.
     * Clients from an excluded subnet are exempt from the batch cap and pacing.
     */
    private void handleRequest(String request, int maxBatchSize) throws InterruptedException {
        //reject requests with null bytes or other control characters
        if(request.indexOf(0) >= 0 || request.chars().anyMatch(c -> c < 32 && c != '\t' && c != '\r' && c != '\n')) {
            log.warn("Rejecting malformed request with control characters");
            writeLine(PARSE_ERROR);
            return;
        }

        if(!ipKey.exempt()) {
            //reject an oversized batch without processing any of it; the session continues, as the stream is intact
            JsonRpcBatch.Summary summary = JsonRpcBatch.summarize(request, maxBatchSize);
            if(summary.items() > maxBatchSize) {
                log.debug("Rejecting batch of more than " + maxBatchSize + " requests from " + clientSocket.getRemoteSocketAddress());
                writeLine(BATCH_TOO_LARGE);
                return;
            }

            //pace silent payments subscribes, each of which starts a scan of the index: a burst beyond the bucket is delayed, not rejected
            int silentPaymentsSubscribes = summary.silentPaymentsSubscribes();
            if(silentPaymentsSubscribes > 0 && silentPaymentsSubscribeBucket.acquire(silentPaymentsSubscribes)) {
                ServerMetrics.silentPaymentsSubscribesPaced(silentPaymentsSubscribes);
            }
        }

        try {
            String response = rpcServer.handle(request, electrumServerService);
            writeLine(response);
        } finally {
            //statuses held for subscribes in this request can follow the response now it has been written
            heldForResponse.forEach(notifier::release);
            heldForResponse.clear();
        }

        runPostResponseTasks();
    }

    /**
     * Begins shutting the session down gracefully: an idle session, waiting for its next request, is closed at once, while one
     * handling a request finishes it, writing the response, and then ends. The session's normal teardown follows either way.
     *
     * Called on the thread shutting the server down, so it never closes a socket itself: an SSL close sends close_notify, which can
     * block without limit behind another write in progress or on a client that has stopped reading. The close runs on its own
     * thread instead, and a close that never completes is bounded by the drain deadline and forceClose().
     */
    public void beginShutdown() {
        boolean idle;
        synchronized(shutdownLock) {
            shuttingDown = true;
            idle = !handlingRequest;
        }
        if(idle) {
            Thread.ofVirtual().name("ElectrumShutdown-" + System.identityHashCode(this)).start(() -> {
                //stop notifications first: a normal close of an SSL socket would wait without limit for a notification write in progress
                notifier.close();
                closeClientSocket(notifier.isDelivering());
            });
        }
    }

    /**
     * Ends the session at once, abortively, as when it has not finished within the shutdown drain deadline. Closing the client socket
     * alone would not end a request thread waiting for the backend or for pacing tokens, so the backend connection is closed and the
     * request thread interrupted too; the session's normal teardown then follows.
     *
     * As with beginShutdown(), the closes run on their own thread, as even an abortive SSL close can block sending close_notify to a
     * peer that has stopped reading. Interrupting the request thread does not block, and also ends any socket I/O it is blocked in.
     */
    public void forceClose() {
        synchronized(shutdownLock) {
            shuttingDown = true;
        }
        Thread thread = requestThread;
        if(thread != null) {
            thread.interrupt();
        }
        Thread.ofVirtual().name("ElectrumForceClose-" + System.identityHashCode(this)).start(() -> {
            notifier.close();
            closeClientSocket(true);
            if(backendSession != null) {
                backendSession.close();
            }
        });
    }

    private boolean isShuttingDown() {
        synchronized(shutdownLock) {
            return shuttingDown;
        }
    }

    /**
     * @return false if the session is shutting down, and the request just read should be dropped
     */
    private boolean startHandling() {
        synchronized(shutdownLock) {
            if(shuttingDown) {
                return false;
            }
            handlingRequest = true;
            return true;
        }
    }

    private void finishHandling() {
        synchronized(shutdownLock) {
            handlingRequest = false;
        }
    }

    /**
     * Sends the request too large error and prepares the connection to close so the client can receive it. The rest of the
     * oversized request is still arriving, and closing a socket with unread input sends a TCP reset, which can make the client
     * discard the error unread. So output is shut down first, sending the error and a FIN at once, and the remaining input is
     * drained (bounded by a byte budget and a deadline) before the session closes. Best effort: a request much larger than the
     * budget still ends in a reset, though the client has had the drain period to read the error.
     * If the notifier is blocked mid-write, the error is not sent, as shutting down output would truncate that notification;
     * the session then ends with an abortive close.
     */
    private void rejectOversizedRequest() {
        notifier.close();
        if(notifier.isDelivering()) {
            return;
        }

        writeLine(REQUEST_TOO_LARGE);
        try {
            clientSocket.shutdownOutput();
            byte[] scratch = new byte[8192];
            InputStream input = clientSocket.getInputStream();
            long deadline = System.nanoTime() + OVERSIZED_REQUEST_DRAIN_NANOS;
            long drained = 0;
            while(drained < OVERSIZED_REQUEST_DRAIN_BYTES) {
                long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if(remainingMillis <= 0) {
                    break;
                }
                clientSocket.setSoTimeout((int)remainingMillis);
                int read = input.read(scratch);
                if(read < 0) {
                    break;
                }
                drained += read;
            }
        } catch(IOException | UnsupportedOperationException e) {
            //timed out, reset by the client, or half-close unsupported by the socket: close regardless
            log.debug("Stopped draining oversized request: {}", e.getMessage());
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

    /**
     * Connects the session's backend connection, returning once the first attempt has completed. If it fails, proxied requests
     * fail fast while the backend session keeps retrying in the background.
     */
    private void startBackendSession() {
        if(backendSession == null) {
            return;
        }

        try {
            backendSession.start();
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
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
     *
     * A new subscription is reserved against the per-IP and global caps in the connection gate; a subscription the client already
     * has is not counted again. Subscriptions are only added and removed on the request thread, so the reservations held always
     * equal the session's subscription count.
     * @return true if the subscription was added, false if the client was already subscribed
     * @throws SubscriptionLimitException if a new subscription would exceed a limit
     */
    public boolean subscribeScriptHash(String scriptHash) throws SubscriptionLimitException {
        boolean reserved = false;
        if(!scriptHashSubscriptions.isSubscribed(scriptHash)) {
            if(!connectionGate.tryReserveSubscription(ipKey)) {
                throw new SubscriptionLimitException("server subscription limit reached");
            }
            reserved = true;
        }

        boolean added = scriptHashSubscriptions.subscribe(scriptHash);
        if(reserved && !added) {
            connectionGate.releaseSubscriptions(ipKey, 1);
        }
        notifier.hold(scriptHash);
        heldForResponse.add(scriptHash);
        return added;
    }

    /**
     * @param sequence the backend read sequence of the subscribe response, see ElectrumTransport.getReadSequence()
     */
    public void recordScriptHashSubscribeResponse(String scriptHash, String status, long sequence) {
        scriptHashSubscriptions.recordSubscribeResponse(scriptHash, status, sequence);
        notifier.discardScriptHash(scriptHash, sequence);
    }

    public void unsubscribeScriptHash(String scriptHash) {
        if(scriptHashSubscriptions.unsubscribe(scriptHash)) {
            connectionGate.releaseSubscriptions(ipKey, 1);
        }
        notifier.discardScriptHash(scriptHash);
    }

    public int getScriptHashSubscriptionCount() {
        return scriptHashSubscriptions.size();
    }

    public boolean hasBackend() {
        return backendSession != null;
    }

    public boolean isBackendConnected() {
        return backendSession != null && backendSession.isConnected();
    }

    /**
     * @return the number of notifications waiting to be delivered to this client
     */
    public int getNotifierQueueDepth() {
        return notifier.getPendingCount();
    }

    @Override
    public boolean isScriptHashSubscribed(String scriptHash) {
        return scriptHashSubscriptions.isSubscribed(scriptHash);
    }

    /**
     * Replaces any existing subscription for the address, cancelling its in-flight scans.
     * @return the new subscription
     */
    public SilentPaymentAddressSubscription subscribeSilentPaymentsAddress(SilentPaymentScanAddress silentPaymentsScanAddress, Set<Integer> labelSet, int startHeight) {
        SilentPaymentAddressSubscription previous = silentPaymentsAddressesSubscribed.get(silentPaymentsScanAddress.toString());
        if(previous != null) {
            previous.invalidateInFlightScans();
        }
        SilentPaymentAddressSubscription subscription = new SilentPaymentAddressSubscription(silentPaymentsScanAddress, labelSet, startHeight);
        silentPaymentsAddressesSubscribed.put(silentPaymentsScanAddress.toString(), subscription);
        return subscription;
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
}
