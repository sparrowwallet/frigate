package com.sparrowwallet.frigate.cli;

import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcMethod;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcParam;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcService;
import com.sparrowwallet.frigate.electrum.SilentPaymentsNotification;
import com.sparrowwallet.frigate.electrum.SilentPaymentsSubscription;
import com.sparrowwallet.frigate.index.TxEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@JsonRpcService
public class SubscriptionService {
    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    @JsonRpcMethod("blockchain.silentpayments.subscribe")
    public void silentPaymentsUpdate(@JsonRpcParam("subscription") SilentPaymentsSubscription subscription, @JsonRpcParam("progress") double progress, @JsonRpcParam("history") List<TxEntry> history) {
        FrigateCli.getEventBus().post(new SilentPaymentsNotification(subscription, progress, history, null));
    }
}
