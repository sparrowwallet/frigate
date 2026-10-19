package com.sparrowwallet.frigate.electrum;

import com.github.arteam.simplejsonrpc.client.JsonRpcClient;
import com.github.arteam.simplejsonrpc.client.Transport;

import java.util.List;

/**
 * Notifications sent to clients. They carry no id, as JSON-RPC notifications do, and their params are positional, as the Electrum protocol specifies
 * and Electrum servers send them; a null status is sent explicitly as null.
 */
public class ElectrumNotificationService {
    private final JsonRpcClient jsonRpcClient;

    public ElectrumNotificationService(Transport transport) {
        this.jsonRpcClient = new JsonRpcClient(transport);
    }

    public void notifyHeaders(ElectrumBlockHeader electrumBlockHeader) {
        notify("blockchain.headers.subscribe", electrumBlockHeader);
    }

    public void notifyScriptHash(String scriptHash, String status) {
        notify("blockchain.scripthash.subscribe", scriptHash, status);
    }

    public void notifySilentPayments(SilentPaymentsSubscription silentPaymentsSubscription, double progress, List<SilentPaymentsTxEntry> history) {
        notify("blockchain.silentpayments.subscribe", silentPaymentsSubscription, progress, history);
    }

    private void notify(String method, Object... params) {
        jsonRpcClient.createNotification().method(method).params(params).execute();
    }
}
