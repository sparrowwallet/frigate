package com.sparrowwallet.frigate.bitcoind;

import com.sparrowwallet.drongo.protocol.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * BlockDataSource implementation that fetches block data and spent output scriptPubKeys
 * via Bitcoin Core JSON-RPC. Uses an LRU cache to minimize getrawtransaction calls.
 */
public class RpcBlockDataSource implements BlockDataSource {
    private static final Logger log = LoggerFactory.getLogger(RpcBlockDataSource.class);

    private final BitcoindClientService rpcService;
    private final Map<HashIndex, byte[]> scriptPubKeyCache;

    public RpcBlockDataSource(BitcoindClientService rpcService, Map<HashIndex, byte[]> scriptPubKeyCache) {
        this.rpcService = rpcService;
        this.scriptPubKeyCache = scriptPubKeyCache;
    }

    @Override
    public BlockWithSpentOutputs getBlockForIndexing(int height) {
        HexFormat hexFormat = HexFormat.of();

        // Fetch the raw block via RPC (single getBlockHash call, reused for the result)
        String blockHash = rpcService.getBlockHash(height);
        String blockHex = (String) rpcService.getBlock(blockHash, 0);
        Block block = new Block(hexFormat.parseHex(blockHex));

        // Single pass: cache outputs and resolve spent scriptPubKeys only for eligible txs.
        // Uses a single shared map across all transactions in the block, matching the
        // original BitcoindClient.updateBlocksIndex() behavior.
        Map<HashIndex, Script> spentScriptPubKeys = new HashMap<>();
        for(Transaction tx : block.getTransactions()) {
            for(int outputIndex = 0; outputIndex < tx.getOutputs().size(); outputIndex++) {
                byte[] scriptPubKeyBytes = tx.getOutputs().get(outputIndex).getScriptBytes();
                addToScriptPubKeyCache(tx.getTxId(), outputIndex, scriptPubKeyBytes);
            }

            if(!tx.isCoinBase() && ScriptUtils.containsTaprootOutput(tx)) {
                for(TransactionInput txInput : tx.getInputs()) {
                    HashIndex hashIndex = new HashIndex(txInput.getOutpoint().getHash(), txInput.getOutpoint().getIndex());
                    spentScriptPubKeys.put(hashIndex, resolveScriptPubKey(hexFormat, hashIndex));
                }
            }
        }

        return new BlockWithSpentOutputs(block, blockHash, spentScriptPubKeys);
    }

    /**
     * Resolve a scriptPubKey for a given outpoint, using the cache first and
     * falling back to getrawtransaction RPC on cache miss.
     */
    private Script resolveScriptPubKey(HexFormat hexFormat, HashIndex hashIndex) {
        Script scriptPubKey = getFromScriptPubKeyCache(hashIndex);
        if(scriptPubKey == null) {
            try {
                String txHex = (String) rpcService.getRawTransaction(hashIndex.getHash().toString(), false);
                Transaction tx = new Transaction(hexFormat.parseHex(txHex));
                TransactionOutput txOutput = tx.getOutputs().get((int) hashIndex.getIndex());
                addToScriptPubKeyCache(hashIndex.getHash(), (int) hashIndex.getIndex(), txOutput.getScriptBytes());
                scriptPubKey = getFromScriptPubKeyCache(hashIndex);
            } catch(Exception e) {
                log.error("Error retrieving scriptPubKey for txid " + hashIndex.getHash() + " output index " + hashIndex.getIndex(), e);
                throw e;
            }
        }
        return scriptPubKey;
    }

    private Script getFromScriptPubKeyCache(HashIndex hashIndex) {
        byte[] scriptPubKeyBytes = scriptPubKeyCache.get(hashIndex);
        if(scriptPubKeyBytes != null) {
            return new Script(scriptPubKeyBytes);
        }
        return null;
    }

    private void addToScriptPubKeyCache(Sha256Hash txid, int outputIndex, byte[] scriptPubKeyBytes) {
        HashIndex hashIndex = new HashIndex(txid, outputIndex);
        if(ScriptUtils.getValidScriptType(scriptPubKeyBytes) != null) {
            scriptPubKeyCache.put(hashIndex, scriptPubKeyBytes);
        } else {
            scriptPubKeyCache.put(hashIndex, new byte[0]);
        }
    }
}
