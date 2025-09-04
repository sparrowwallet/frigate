package com.sparrowwallet.frigate.electrum;

import com.sparrowwallet.frigate.SubscriptionStatus;
import com.sparrowwallet.frigate.index.TxEntry;

import java.util.List;

public record SilentPaymentsNotification(SilentPaymentsSubscription subscription, double progress, List<TxEntry> history, SubscriptionStatus status) {

}
