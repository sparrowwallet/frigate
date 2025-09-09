package com.sparrowwallet.frigate.index;

public record SilentPaymentsBlocksIndexUpdate(int fromBlockHeight, int toBlockHeight, int totalTransactions) {}
