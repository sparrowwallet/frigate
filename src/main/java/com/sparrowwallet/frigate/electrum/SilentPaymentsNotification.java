package com.sparrowwallet.frigate.electrum;

import java.util.List;

public record SilentPaymentsNotification(SilentPaymentsSubscription subscription, double progress, List<SilentPaymentsTxEntry> history) {

}
