package com.sparrowwallet.frigate.bitcoind.reader;

import com.sparrowwallet.drongo.protocol.Block;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

public class BlockFileReader {
    // Bitcoin consensus: max block weight is 4M weight units = max ~4MB serialized
    private static final int MAX_BLOCK_SIZE = 4_000_000;

    private final Path blocksDir;
    private final XorObfuscation xor;

    public BlockFileReader(Path blocksDir, XorObfuscation xor) {
        this.blocksDir = blocksDir;
        this.xor = xor;
    }

    /**
     * Read raw block bytes from the given file number and data position.
     */
    public byte[] readBlock(int fileNumber, int dataPos) throws IOException {
        Path blockFile = blocksDir.resolve(String.format("blk%05d.dat", fileNumber));

        try(RandomAccessFile raf = new RandomAccessFile(blockFile.toFile(), "r")) {
            raf.seek(dataPos - 4);
            byte[] sizeBytes = new byte[4];
            raf.readFully(sizeBytes);
            xor.deobfuscate(sizeBytes, dataPos - 4);
            int blockSize = ByteBuffer.wrap(sizeBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();

            if(blockSize < 0 || blockSize > MAX_BLOCK_SIZE) {
                throw new IOException("Invalid block size " + blockSize + " at file " + fileNumber + " pos " + dataPos);
            }

            byte[] blockData = new byte[blockSize];
            raf.readFully(blockData);
            xor.deobfuscate(blockData, dataPos);

            return blockData;
        }
    }

    /**
     * Read and parse a block into a Drongo Block object.
     */
    public Block readAndParseBlock(int fileNumber, int dataPos) throws IOException {
        return new Block(readBlock(fileNumber, dataPos));
    }
}
