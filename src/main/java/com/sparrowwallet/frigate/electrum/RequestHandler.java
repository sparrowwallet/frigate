package com.sparrowwallet.frigate.electrum;

import com.github.arteam.simplejsonrpc.client.JsonRpcClient;
import com.github.arteam.simplejsonrpc.server.JsonRpcServer;
import com.google.common.eventbus.Subscribe;
import com.sparrowwallet.drongo.silentpayments.SilentPaymentScanAddress;
import com.sparrowwallet.frigate.Frigate;
import com.sparrowwallet.frigate.SubscriptionStatus;
import com.sparrowwallet.frigate.bitcoind.BitcoindClient;
import com.sparrowwallet.frigate.index.Index;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class RequestHandler implements Runnable, SubscriptionStatus {
    private static final Logger log = LoggerFactory.getLogger(RequestHandler.class);
    private final Socket clientSocket;
    private final ElectrumServerService electrumServerService;
    private final JsonRpcServer rpcServer = new JsonRpcServer();
    private final AtomicBoolean disconnected = new AtomicBoolean(false);

    private boolean connected;
    private boolean headersSubscribed;
    private final Set<String> scriptHashesSubscribed = new HashSet<>();
    private final Map<String, SilentPaymentScanAddress> silentPaymentsAddressesSubscribed = new HashMap<>();

    public RequestHandler(Socket clientSocket, BitcoindClient bitcoindClient, Index index) {
        this.clientSocket = clientSocket;
        this.electrumServerService = new ElectrumServerService(bitcoindClient, this, index);
    }

    public void run() {
        Frigate.getEventBus().register(this);
        this.connected = true;

        try {
            InputStream input  = clientSocket.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));

            OutputStream output = clientSocket.getOutputStream();
            PrintWriter out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8)));

            while(true) {
                String request = reader.readLine();
                if(request == null) {
                    break;
                }

                String response = rpcServer.handle(request, electrumServerService);
                out.println(response);
                out.flush();
            }
        } catch(IOException e) {
            log.error("Could not communicate with client socket", e);
        } finally {
            this.connected = false;
            this.disconnected.set(true);
            Frigate.getEventBus().unregister(this);
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

    @Override
    public boolean isScriptHashSubscribed(String scriptHash) {
        return scriptHashesSubscribed.contains(scriptHash);
    }

    public void subscribeSilentPaymentsAddress(SilentPaymentScanAddress silentPaymentsScanAddress) {
        silentPaymentsAddressesSubscribed.put(silentPaymentsScanAddress.toString(), silentPaymentsScanAddress);
    }

    public void unsubscribeSilentPaymentsAddress(SilentPaymentScanAddress silentPaymentsScanAddress) {
        silentPaymentsAddressesSubscribed.remove(silentPaymentsScanAddress.toString());
    }

    @Override
    public boolean isSilentPaymentsAddressSubscribed(String silentPaymentsAddress) {
        return silentPaymentsAddressesSubscribed.containsKey(silentPaymentsAddress);
    }

    @Subscribe
    public void newBlock(ElectrumBlockHeader electrumBlockHeader) {
        if(isHeadersSubscribed()) {
            ElectrumNotificationTransport electrumNotificationTransport = new ElectrumNotificationTransport(clientSocket);
            JsonRpcClient jsonRpcClient = new JsonRpcClient(electrumNotificationTransport);
            jsonRpcClient.onDemand(ElectrumNotificationService.class).notifyHeaders(electrumBlockHeader);
        }
    }

    @Subscribe
    public void scriptHashStatus(ScriptHashStatus scriptHashStatus) {
        if(isScriptHashSubscribed(scriptHashStatus.scriptHash())) {
            ElectrumNotificationTransport electrumNotificationTransport = new ElectrumNotificationTransport(clientSocket);
            JsonRpcClient jsonRpcClient = new JsonRpcClient(electrumNotificationTransport);
            jsonRpcClient.onDemand(ElectrumNotificationService.class).notifyScriptHash(scriptHashStatus.scriptHash(), scriptHashStatus.status());
        }
    }

    @Subscribe
    public void silentPaymentsNotification(SilentPaymentsNotification notification) {
        if(isSilentPaymentsAddressSubscribed(notification.subscription().address()) && notification.status() == this) {
            ElectrumNotificationTransport electrumNotificationTransport = new ElectrumNotificationTransport(clientSocket);
            JsonRpcClient jsonRpcClient = new JsonRpcClient(electrumNotificationTransport);
            jsonRpcClient.onDemand(ElectrumNotificationService.class).notifySilentPayments(notification.subscription(), notification.progress(), notification.history());
        }
    }
}
