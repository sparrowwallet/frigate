package com.sparrowwallet.frigate.bitcoind;

import com.sparrowwallet.drongo.wallet.BlockTransaction;

import java.util.Map;

record BlockIndexResult(int height, String blockHash, BlockWithSpentOutputs blockData, Map<BlockTransaction, byte[]> eligibleTransactions) {}
