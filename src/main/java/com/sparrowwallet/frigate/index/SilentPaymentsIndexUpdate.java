package com.sparrowwallet.frigate.index;

public record SilentPaymentsIndexUpdate(int fromBlockHeight, int toBlockHeight, int totalTransactions) {}
