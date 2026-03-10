package com.sparrowwallet.frigate.bitcoind.reader;

import com.sparrowwallet.drongo.protocol.Sha256Hash;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class UndoReader {
    // Generous upper bound — largest observed undo data is ~4MB for the largest blocks
    private static final int MAX_UNDO_SIZE = 8_000_000;

    private final Path blocksDir;
    private final XorObfuscation xor;

    public UndoReader(Path blocksDir, XorObfuscation xor) {
        this.blocksDir = blocksDir;
        this.xor = xor;
    }

    /** A single spent output from the undo data. */
    public record SpentOutput(long amount, byte[] scriptPubKey, int height, boolean coinbase) {}

    /** All spent outputs for one transaction. */
    public record TxUndo(List<SpentOutput> prevouts) {}

    /** All undo data for one block. */
    public record BlockUndo(List<TxUndo> txUndos) {}

    /**
     * Read undo data for a block.
     *
     * @param fileNumber    the rev file number (same as the blk file number)
     * @param undoPos       byte offset in rev?????.dat (past the 8-byte storage header)
     * @param prevBlockHash 32-byte hash of the previous block (LE, wire byte order),
     *                      used to verify the undo data checksum. Pass null to skip verification.
     */
    public BlockUndo readBlockUndo(int fileNumber, int undoPos, byte[] prevBlockHash) throws IOException {
        Path undoFile = blocksDir.resolve(String.format("rev%05d.dat", fileNumber));

        try(RandomAccessFile raf = new RandomAccessFile(undoFile.toFile(), "r")) {
            raf.seek(undoPos - 4);
            byte[] sizeBytes = new byte[4];
            raf.readFully(sizeBytes);
            xor.deobfuscate(sizeBytes, undoPos - 4);
            int undoSize = ByteBuffer.wrap(sizeBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();

            if(undoSize < 0 || undoSize > MAX_UNDO_SIZE) {
                throw new IOException("Invalid undo size " + undoSize + " at file " + fileNumber + " pos " + undoPos);
            }

            byte[] undoData = new byte[undoSize];
            raf.readFully(undoData);
            xor.deobfuscate(undoData, undoPos);

            // Verify checksum: SHA256d(prevBlockHash + undoData)
            if(prevBlockHash != null) {
                byte[] storedChecksum = new byte[32];
                raf.readFully(storedChecksum);
                xor.deobfuscate(storedChecksum, undoPos + undoSize);

                byte[] computed = Sha256Hash.hashTwice(prevBlockHash, undoData);
                if(!Arrays.equals(storedChecksum, computed)) {
                    throw new IOException("Undo data checksum mismatch at file " + fileNumber + " pos " + undoPos);
                }
            }

            return parseBlockUndo(new ByteArrayInputStream(undoData));
        }
    }

    private BlockUndo parseBlockUndo(InputStream in) throws IOException {
        long numTxUndo = readCompactSize(in);
        List<TxUndo> txUndos = new ArrayList<>((int) numTxUndo);

        for(int i = 0; i < numTxUndo; i++) {
            long numPrevouts = readCompactSize(in);
            List<SpentOutput> prevouts = new ArrayList<>((int) numPrevouts);

            for(int j = 0; j < numPrevouts; j++) {
                long nCode = readCoreVarInt(in);
                int height = (int) (nCode >> 1);
                boolean coinbase = (nCode & 1) != 0;

                if(height > 0) {
                    readCoreVarInt(in); // legacy nVersion, discard
                }

                long compressedAmount = readCoreVarInt(in);
                long amount = decompressAmount(compressedAmount);

                int scriptType = (int) readCoreVarInt(in);
                byte[] scriptPubKey;
                if(scriptType < 6) {
                    int specialSize = getSpecialScriptSize(scriptType);
                    byte[] compressed = in.readNBytes(specialSize);
                    if(compressed.length < specialSize) {
                        throw new EOFException("Truncated compressed script data");
                    }
                    scriptPubKey = decompressScript(scriptType, compressed);
                } else {
                    int rawLen = scriptType - 6;
                    byte[] raw = in.readNBytes(rawLen);
                    if(raw.length < rawLen) {
                        throw new EOFException("Truncated raw script data");
                    }
                    scriptPubKey = raw;
                }

                prevouts.add(new SpentOutput(amount, scriptPubKey, height, coinbase));
            }

            txUndos.add(new TxUndo(prevouts));
        }

        return new BlockUndo(txUndos);
    }

    /**
     * Bitcoin Core's VARINT (base-128 with continuation bits).
     * NOT the same as CompactSize/VarInt used in transactions.
     */
    static long readCoreVarInt(InputStream in) throws IOException {
        long n = 0;
        while(true) {
            int b = in.read();
            if(b < 0) {
                throw new EOFException();
            }
            n = (n << 7) | (b & 0x7F);
            if((b & 0x80) == 0) {
                return n;
            }
            n++;
        }
    }

    /**
     * CompactSize (same as Drongo's VarInt) — used for vector lengths in undo data.
     */
    static long readCompactSize(InputStream in) throws IOException {
        int first = in.read();
        if(first < 0) {
            throw new EOFException();
        }
        first &= 0xFF;
        if(first < 253) {
            return first;
        }
        if(first == 253) {
            return readLE(in, 2);
        }
        if(first == 254) {
            return readLE(in, 4);
        }
        return readLE(in, 8);
    }

    private static long readLE(InputStream in, int bytes) throws IOException {
        long value = 0;
        for(int i = 0; i < bytes; i++) {
            int b = in.read();
            if(b < 0) {
                throw new EOFException();
            }
            value |= ((long) b) << (i * 8);
        }
        return value;
    }

    static long decompressAmount(long x) {
        if(x == 0) {
            return 0;
        }
        x--;
        int e = (int) (x % 10);
        x /= 10;
        long n;
        if(e < 9) {
            int d = (int) (x % 9) + 1;
            x /= 9;
            n = x * 10 + d;
        } else {
            n = x + 1;
        }
        for(int i = 0; i < e; i++) {
            n *= 10;
        }
        return n;
    }

    static int getSpecialScriptSize(int type) {
        if(type == 0 || type == 1) {
            return 20;
        }
        if(type >= 2 && type <= 5) {
            return 32;
        }
        return 0;
    }

    static byte[] decompressScript(int type, byte[] compressed) {
        switch(type) {
            case 0: { // P2PKH
                byte[] script = new byte[25];
                script[0] = 0x76; // OP_DUP
                script[1] = (byte) 0xa9; // OP_HASH160
                script[2] = 0x14; // push 20 bytes
                System.arraycopy(compressed, 0, script, 3, 20);
                script[23] = (byte) 0x88; // OP_EQUALVERIFY
                script[24] = (byte) 0xac; // OP_CHECKSIG
                return script;
            }
            case 1: { // P2SH
                byte[] script = new byte[23];
                script[0] = (byte) 0xa9; // OP_HASH160
                script[1] = 0x14; // push 20 bytes
                System.arraycopy(compressed, 0, script, 2, 20);
                script[22] = (byte) 0x87; // OP_EQUAL
                return script;
            }
            case 2: case 3: { // P2PK compressed
                byte[] script = new byte[35];
                script[0] = 0x21; // push 33 bytes
                script[1] = (byte) type; // 0x02 or 0x03
                System.arraycopy(compressed, 0, script, 2, 32);
                script[34] = (byte) 0xac; // OP_CHECKSIG
                return script;
            }
            case 4: case 5: { // P2PK uncompressed — not an SP-eligible input type
                return new byte[]{(byte) 0xac};
            }
            default: { // Raw script (type >= 6, length = type - 6)
                return compressed;
            }
        }
    }
}
