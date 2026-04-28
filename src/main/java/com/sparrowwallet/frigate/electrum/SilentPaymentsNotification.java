package com.sparrowwallet.frigate.electrum;

import com.sparrowwallet.frigate.SubscriptionStatus;

import java.util.List;

public record SilentPaymentsNotification(SilentPaymentsSubscription subscription, double progress, List<SilentPaymentsTxEntry> history, SubscriptionStatus status) {

}
