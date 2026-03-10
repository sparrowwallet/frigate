package com.sparrowwallet.frigate.bitcoind;

import java.io.Closeable;
import java.io.IOException;

/**
 * Provides block data and spent output information for Silent Payments indexing.
 * Implementations may use RPC, flat files, or other data sources.
 */
public interface BlockDataSource extends Closeable {
    /**
     * Get block data, block hash, and spent output scriptPubKeys needed for indexing
     * at the given height. The returned spentScriptPubKeys map covers all inputs of
     * eligible transactions (non-coinbase with taproot outputs) in the block.
     */
    BlockWithSpentOutputs getBlockForIndexing(int height);

    @Override
    default void close() throws IOException {}
}
