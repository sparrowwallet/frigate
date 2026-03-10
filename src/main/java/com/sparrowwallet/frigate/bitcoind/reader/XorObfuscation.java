package com.sparrowwallet.frigate.bitcoind.reader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class XorObfuscation {
    private final byte[] key;

    public XorObfuscation(Path blocksDir) throws IOException {
        Path xorFile = blocksDir.resolve("xor.dat");
        if(Files.exists(xorFile)) {
            this.key = Files.readAllBytes(xorFile);
        } else {
            this.key = new byte[8];
        }
    }

    /**
     * XOR-deobfuscate data in place. The XOR key repeats every 8 bytes
     * relative to the file position.
     */
    public void deobfuscate(byte[] data, long fileOffset) {
        if(isNull()) {
            return;
        }
        for(int i = 0; i < data.length; i++) {
            data[i] ^= key[(int) ((fileOffset + i) % key.length)];
        }
    }

    public boolean isNull() {
        for(byte b : key) {
            if(b != 0) {
                return false;
            }
        }
        return true;
    }
}
