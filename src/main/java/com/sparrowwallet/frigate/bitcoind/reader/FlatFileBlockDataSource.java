package com.sparrowwallet.frigate.bitcoind.reader;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.frigate.bitcoind.BlockDataSource;
import com.sparrowwallet.frigate.bitcoind.BlockWithSpentOutputs;
import com.sparrowwallet.frigate.bitcoind.ScriptUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class FlatFileBlockDataSource implements BlockDataSource {
    private static final Logger log = LoggerFactory.getLogger(FlatFileBlockDataSource.class);

    private final Path indexDir;
    private final BlockFileReader blockReader;
    private final UndoReader undoReader;
    private final Map<HashIndex, byte[]> scriptPubKeyCache;
    private volatile BlockIndex blockIndex;

    /**
     * @param blocksDir         path to the blocks/ directory
     * @param indexDir          path to the blocks/index/ directory (containing headers.dat)
     * @param scriptPubKeyCache shared cache with BitcoindClient for mempool indexing benefit
     */
    public FlatFileBlockDataSource(Path blocksDir, Path indexDir, Map<HashIndex, byte[]> scriptPubKeyCache) throws IOException {
        this.indexDir = indexDir;
        XorObfuscation xor = new XorObfuscation(blocksDir);
        this.blockReader = new BlockFileReader(blocksDir, xor);
        this.undoReader = new UndoReader(blocksDir, xor);
        this.scriptPubKeyCache = scriptPubKeyCache;
        this.blockIndex = BlockIndex.load(indexDir.resolve("headers.dat"));
        log.info("Loaded flat file block index with {} entries (max height {})", blockIndex.size(), blockIndex.getMaxHeight());
    }

    @Override
    public BlockWithSpentOutputs getBlockForIndexing(int height) {
        BlockIndex idx = this.blockIndex; // snapshot for thread safety

        if(!idx.has(height)) {
            if(height > idx.getMaxHeight()) {
                reloadIndex();
                idx = this.blockIndex;
            }
            if(!idx.has(height)) {
                throw new IllegalArgumentException("No block index entry for height " + height);
            }
        }

        try {
            Block block = blockReader.readAndParseBlock(idx.getFileNumber(height), idx.getDataPos(height));
            String blockHash = block.getHash().toString();

            // Genesis block (height 0) has no undo data and no non-coinbase transactions
            if(height == 0) {
                return new BlockWithSpentOutputs(block, blockHash, Map.of());
            }

            if(!idx.hasUndo(height)) {
                throw new IOException("Block at height " + height + " has no undo data on disk");
            }

            // Pass the previous block hash in LE (wire/internal) byte order for checksum verification.
            // Drongo stores hashes in BE (display) order internally (readHash() calls wrapReversed()),
            // so getReversedBytes() gives us the LE bytes that match Bitcoin Core's uint256 representation.
            byte[] prevBlockHash = block.getBlockHeader().getPrevBlockHash().getReversedBytes();
            UndoReader.BlockUndo undo = undoReader.readBlockUndo(idx.getFileNumber(height), idx.getUndoPos(height), prevBlockHash);

            // Single pass: for each eligible tx, get spent scriptPubKeys from undo data.
            // undo.txUndos() is parallel to block.getTransactions() minus the coinbase:
            // undo index 0 = tx index 1, undo index 1 = tx index 2, etc.
            Map<HashIndex, Script> spentScriptPubKeys = new HashMap<>();
            for(int txIdx = 1; txIdx < block.getTransactions().size(); txIdx++) {
                Transaction tx = block.getTransactions().get(txIdx);
                if(!ScriptUtils.containsTaprootOutput(tx)) {
                    continue;
                }

                UndoReader.TxUndo txUndo = undo.txUndos().get(txIdx - 1);
                for(int inputIdx = 0; inputIdx < tx.getInputs().size(); inputIdx++) {
                    TransactionInput input = tx.getInputs().get(inputIdx);
                    HashIndex hashIndex = new HashIndex(input.getOutpoint().getHash(), input.getOutpoint().getIndex());
                    spentScriptPubKeys.put(hashIndex, new Script(txUndo.prevouts().get(inputIdx).scriptPubKey()));
                }
            }

            return new BlockWithSpentOutputs(block, blockHash, spentScriptPubKeys);
        } catch(IOException e) {
            throw new RuntimeException("Failed to read block at height " + height, e);
        }
    }

    @Override
    public int getAvailableHeight() {
        return blockIndex.getMaxHeight();
    }

    @Override
    public void populateCache(BlockWithSpentOutputs blockData) {
        for(Transaction tx : blockData.block().getTransactions()) {
            for(int outputIndex = 0; outputIndex < tx.getOutputs().size(); outputIndex++) {
                byte[] scriptPubKeyBytes = tx.getOutputs().get(outputIndex).getScriptBytes();
                addToScriptPubKeyCache(tx.getTxId(), outputIndex, scriptPubKeyBytes);
            }
        }
    }

    private synchronized void reloadIndex() {
        try {
            this.blockIndex = BlockIndex.load(indexDir.resolve("headers.dat"));
            log.debug("Reloaded flat file block index, max height {}", blockIndex.getMaxHeight());
        } catch(IOException e) {
            log.warn("Failed to reload flat file block index", e);
        }
    }

    private void addToScriptPubKeyCache(Sha256Hash txid, int outputIndex, byte[] scriptPubKeyBytes) {
        HashIndex hashIndex = new HashIndex(txid, outputIndex);
        if(ScriptUtils.getValidScriptType(scriptPubKeyBytes) != null) {
            scriptPubKeyCache.put(hashIndex, scriptPubKeyBytes);
        } else {
            scriptPubKeyCache.put(hashIndex, new byte[0]);
        }
    }

    @Override
    public void close() throws IOException {
        // BlockFileReader and UndoReader open/close file handles per call, nothing to clean up
    }

    /**
     * Resolve the blocks directory for the current network.
     * Bitcoin Core has never used a mainnet/ subdirectory — blocks are always at
     * {datadir}/blocks/ on mainnet. Network.getHome() returns "mainnet" (a Drongo
     * convention), so the resolve("mainnet") path will never exist and the fallback
     * to {datadir}/blocks/ always triggers.
     */
    public static Path resolveBlocksDir(Path dataDir) {
        String home = Network.get().getHome();
        Path blocksDir = dataDir.resolve(home).resolve("blocks");
        if(!Files.isDirectory(blocksDir) && Network.get() == Network.MAINNET) {
            blocksDir = dataDir.resolve("blocks");
        }
        return blocksDir;
    }

    /**
     * Check if the flat file block index is available (PR #32427 format).
     */
    public static boolean isAvailable(Path dataDir) {
        Path blocksDir = resolveBlocksDir(dataDir);
        return Files.exists(blocksDir.resolve("index").resolve("headers.dat"));
    }
}
