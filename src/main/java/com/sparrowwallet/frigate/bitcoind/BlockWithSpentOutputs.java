package com.sparrowwallet.frigate.bitcoind;

import com.sparrowwallet.drongo.protocol.Block;
import com.sparrowwallet.drongo.protocol.HashIndex;
import com.sparrowwallet.drongo.protocol.Script;

import java.util.Map;

/**
 * A block together with the block hash and the spent scriptPubKeys accumulated
 * across all eligible transactions (non-coinbase with taproot outputs).
 * The spentScriptPubKeys map contains entries for every input of every eligible
 * transaction in the block, keyed by outpoint.
 */
public record BlockWithSpentOutputs(Block block, String blockHash, Map<HashIndex, Script> spentScriptPubKeys) {}
