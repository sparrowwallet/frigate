package com.sparrowwallet.frigate.bitcoind.reader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32C;

public class BlockIndex {
    private static final int MAGIC = 0x1d5e2eb2;
    private static final int VERSION = 1;
    private static final int HEADER_SIZE = 8;
    private static final int ENTRY_DATA_SIZE = 112;
    private static final int CHECKSUM_SIZE = 4;
    private static final int ENTRY_TOTAL_SIZE = ENTRY_DATA_SIZE + CHECKSUM_SIZE;

    // nStatus bits (from chain.h BlockStatus enum)
    private static final int BLOCK_HAVE_DATA = 8;
    private static final int BLOCK_HAVE_UNDO = 16;

    // Sanity cap: no Bitcoin network will reach this height in our lifetimes.
    // Protects against corrupt entries causing OOM via new int[Integer.MAX_VALUE].
    private static final int MAX_SANE_HEIGHT = 10_000_000;

    // Parallel arrays indexed by height. Unoccupied slots have fileNumber == -1.
    private final int[] status;
    private final int[] fileNumber;
    private final int[] dataPos;
    private final int[] undoPos;
    private final int maxHeight;
    private int entryCount;

    private BlockIndex(int maxHeight) {
        int size = maxHeight + 1;
        this.status = new int[size];
        this.fileNumber = new int[size];
        this.dataPos = new int[size];
        this.undoPos = new int[size];
        this.maxHeight = maxHeight;
        Arrays.fill(fileNumber, -1);
    }

    /**
     * Parse all entries from headers.dat and build a height-indexed structure.
     * Only includes entries that have block data on disk (BLOCK_HAVE_DATA).
     * Non-genesis entries without undo data are excluded.
     */
    public static BlockIndex load(Path headersFile) throws IOException {
        // First pass: find max height to size the arrays
        int maxHeight = findMaxHeight(headersFile);
        if(maxHeight < 0) {
            throw new IOException("Empty headers.dat");
        }

        BlockIndex index = new BlockIndex(maxHeight);

        try(FileChannel channel = FileChannel.open(headersFile)) {
            long fileSize = channel.size();
            readAndVerifyFileHeader(channel);

            ByteBuffer buf = ByteBuffer.allocate(ENTRY_TOTAL_SIZE).order(ByteOrder.LITTLE_ENDIAN);
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
                ByteBuffer posBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
                posBuf.putLong(entryFilePos);
                crc.update(posBuf.array());

                if((int) crc.getValue() != storedChecksum) {
                    throw new IOException("CRC32c mismatch at offset " + entryFilePos);
                }

                ByteBuffer entry = ByteBuffer.wrap(dataBytes).order(ByteOrder.LITTLE_ENDIAN);
                int height = entry.getInt();
                int entryStatus = entry.getInt();
                entry.getInt(); // nTx — not needed
                int entryFileNumber = entry.getInt();
                int entryDataPos = entry.getInt();
                int entryUndoPos = entry.getInt();
                // remaining 88 bytes: headerPos(8) + block header(80) — not stored

                boolean hasData = (entryStatus & BLOCK_HAVE_DATA) != 0;
                boolean hasUndo = (entryStatus & BLOCK_HAVE_UNDO) != 0;

                if(hasData && (height == 0 || hasUndo) && height >= 0 && height <= maxHeight) {
                    if(index.fileNumber[height] == -1) {
                        index.entryCount++;
                    }
                    index.status[height] = entryStatus;
                    index.fileNumber[height] = entryFileNumber;
                    index.dataPos[height] = entryDataPos;
                    index.undoPos[height] = entryUndoPos;
                }
            }
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
