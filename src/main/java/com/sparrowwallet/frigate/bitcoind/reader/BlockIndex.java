package com.sparrowwallet.frigate.bitcoind.reader;

import com.sparrowwallet.drongo.protocol.Sha256Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32C;

public class BlockIndex {
    private static final Logger log = LoggerFactory.getLogger(BlockIndex.class);

    private static final int MAGIC = 0x1d5e2eb2;
    private static final int VERSION = 1;
    private static final int HEADER_SIZE = 8;
    private static final int ENTRY_DATA_SIZE = 112;
    private static final int CHECKSUM_SIZE = 4;
    private static final int ENTRY_TOTAL_SIZE = ENTRY_DATA_SIZE + CHECKSUM_SIZE;

    // nStatus bits (from chain.h BlockStatus enum)
    private static final int BLOCK_HAVE_DATA = 8;
    private static final int BLOCK_HAVE_UNDO = 16;
    private static final int BLOCK_FAILED_VALID = 32;
    private static final int BLOCK_FAILED_CHILD = 64;
    private static final int BLOCK_FAILED_MASK = BLOCK_FAILED_VALID | BLOCK_FAILED_CHILD;

    // Entry layout: 80-byte block header starts at byte offset 32 within the 112-byte entry data.
    // prevHash is at bytes 4-35 of the block header (offset 36 in the entry).
    private static final int BLOCK_HEADER_OFFSET = 32;
    private static final int BLOCK_HEADER_SIZE = 80;
    private static final int PREV_HASH_OFFSET = 36;
    private static final int HASH_SIZE = 32;

    // Sanity cap: no Bitcoin network will reach this height in our lifetimes.
    // Protects against corrupt entries causing OOM via new int[Integer.MAX_VALUE].
    private static final int MAX_SANE_HEIGHT = 10_000_000;

    // Parallel arrays indexed by height. Unoccupied slots have fileNumber == -1.
    private final int[] status;
    private final int[] fileNumber;
    private final int[] dataPos;
    private final int[] undoPos;
    private int maxHeight;
    private int entryCount;

    private record ParsedEntry(int height, int status, int fileNumber, int dataPos, int undoPos, Sha256Hash prevHash) {}

    private BlockIndex(int arraySize) {
        int size = arraySize + 1;
        this.status = new int[size];
        this.fileNumber = new int[size];
        this.dataPos = new int[size];
        this.undoPos = new int[size];
        this.maxHeight = arraySize;
        Arrays.fill(fileNumber, -1);
    }

    /**
     * Parse all entries from headers.dat and build a height-indexed structure.
     * Only includes blocks on the best chain (determined by following prevHash links
     * backwards from the tip). This correctly excludes stale/orphan blocks that share
     * the same height as best-chain blocks.
     */
    public static BlockIndex load(Path headersFile) throws IOException {
        // First pass: find max height to size the arrays
        int rawMaxHeight = findMaxHeight(headersFile);
        if(rawMaxHeight < 0) {
            throw new IOException("Empty headers.dat");
        }

        BlockIndex index = new BlockIndex(rawMaxHeight);

        // Second pass: parse all entries, compute block hashes, and build a chain map
        Map<Sha256Hash, ParsedEntry> entryMap = new HashMap<>();
        Sha256Hash bestTipHash = null;
        int bestTipHeight = -1;

        try(FileChannel channel = FileChannel.open(headersFile)) {
            long fileSize = channel.size();
            readAndVerifyFileHeader(channel);

            ByteBuffer buf = ByteBuffer.allocate(ENTRY_TOTAL_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer posBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            CRC32C crc = new CRC32C();

            while(channel.position() + ENTRY_TOTAL_SIZE <= fileSize) {
                long entryFilePos = channel.position();
                buf.clear();
                int bytesRead = channel.read(buf);
                if(bytesRead < ENTRY_TOTAL_SIZE) {
                    break;
                }
                buf.flip();

                byte[] dataBytes = new byte[ENTRY_DATA_SIZE];
                buf.get(dataBytes);
                int storedChecksum = buf.getInt();

                crc.reset();
                crc.update(dataBytes);
                posBuf.clear();
                posBuf.putLong(entryFilePos);
                crc.update(posBuf.array());

                if((int) crc.getValue() != storedChecksum) {
                    throw new IOException("CRC32c mismatch at offset " + entryFilePos);
                }

                ByteBuffer entry = ByteBuffer.wrap(dataBytes).order(ByteOrder.LITTLE_ENDIAN);
                int height = entry.getInt();
                int entryStatus = entry.getInt();

                if(height < 0 || height > rawMaxHeight) {
                    continue;
                }
                if((entryStatus & BLOCK_FAILED_MASK) != 0) {
                    continue;
                }

                // Compute block hash from the 80-byte block header embedded in the entry
                Sha256Hash blockHash = Sha256Hash.wrapReversed(Sha256Hash.hashTwice(dataBytes, BLOCK_HEADER_OFFSET, BLOCK_HEADER_SIZE));

                // Extract prevHash (32 bytes in LE wire order)
                byte[] prevHashBytes = new byte[HASH_SIZE];
                System.arraycopy(dataBytes, PREV_HASH_OFFSET, prevHashBytes, 0, HASH_SIZE);
                Sha256Hash prevHash = Sha256Hash.wrapReversed(prevHashBytes);

                entry.getInt(); // nTx
                int entryFileNumber = entry.getInt();
                int entryDataPos = entry.getInt();
                int entryUndoPos = entry.getInt();

                entryMap.put(blockHash, new ParsedEntry(height, entryStatus, entryFileNumber, entryDataPos, entryUndoPos, prevHash));

                // Track best tip candidate: highest height with block data on disk
                if((entryStatus & BLOCK_HAVE_DATA) != 0 && height > bestTipHeight) {
                    bestTipHeight = height;
                    bestTipHash = blockHash;
                }
            }
        }

        if(bestTipHash == null) {
            throw new IOException("No valid entries found in headers.dat");
        }

        // Walk backwards from the tip following prevHash links to identify the best chain.
        // Only populate the parallel arrays for blocks on this chain.
        int bestChainMaxHeight = -1;
        Sha256Hash current = bestTipHash;
        while(current != null) {
            ParsedEntry pe = entryMap.get(current);
            if(pe == null) {
                break;
            }

            int h = pe.height;
            boolean hasData = (pe.status & BLOCK_HAVE_DATA) != 0;
            boolean hasUndo = (pe.status & BLOCK_HAVE_UNDO) != 0;

            if(hasData && (h == 0 || hasUndo) && h >= 0 && h <= rawMaxHeight) {
                if(index.fileNumber[h] == -1) {
                    index.entryCount++;
                }
                index.status[h] = pe.status;
                index.fileNumber[h] = pe.fileNumber;
                index.dataPos[h] = pe.dataPos;
                index.undoPos[h] = pe.undoPos;
                bestChainMaxHeight = Math.max(bestChainMaxHeight, h);
            }

            current = pe.prevHash;
        }

        if(bestChainMaxHeight < 0) {
            throw new IOException("Chain walk from tip found no indexable blocks in headers.dat");
        }

        index.maxHeight = bestChainMaxHeight;

        int staleEntries = entryMap.size() - index.entryCount;
        if(staleEntries > 0) {
            log.debug("Excluded {} stale/orphan block entries from index", staleEntries);
        }

        return index;
    }

    /**
     * Quick scan to find the maximum height in the file.
     * Skips entries with heights outside [0, MAX_SANE_HEIGHT) to guard against corruption.
     */
    private static int findMaxHeight(Path headersFile) throws IOException {
        int maxHeight = -1;

        try(FileChannel channel = FileChannel.open(headersFile)) {
            long fileSize = channel.size();
            readAndVerifyFileHeader(channel);

            ByteBuffer buf = ByteBuffer.allocate(ENTRY_TOTAL_SIZE).order(ByteOrder.LITTLE_ENDIAN);

            while(channel.position() + ENTRY_TOTAL_SIZE <= fileSize) {
                buf.clear();
                int bytesRead = channel.read(buf);
                if(bytesRead < ENTRY_TOTAL_SIZE) {
                    break;
                }
                buf.flip();
                int height = buf.getInt();
                if(height >= 0 && height < MAX_SANE_HEIGHT && height > maxHeight) {
                    maxHeight = height;
                }
            }
        }

        return maxHeight;
    }

    private static void readAndVerifyFileHeader(FileChannel channel) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        channel.read(header);
        header.flip();
        if(header.getInt() != MAGIC) {
            throw new IOException("Invalid headers.dat magic");
        }
        if(header.getInt() != VERSION) {
            throw new IOException("Unsupported headers.dat version");
        }
    }

    /**
     * Check if an entry exists at the given height.
     */
    public boolean has(int height) {
        return height >= 0 && height <= maxHeight && fileNumber[height] != -1;
    }

    public int getFileNumber(int height) {
        return fileNumber[height];
    }

    public int getDataPos(int height) {
        return dataPos[height];
    }

    public int getUndoPos(int height) {
        return undoPos[height];
    }

    public boolean hasUndo(int height) {
        return (status[height] & BLOCK_HAVE_UNDO) != 0;
    }

    public int getMaxHeight() {
        return maxHeight;
    }

    public int size() {
        return entryCount;
    }
}
