package com.sparrowwallet.frigate.io;

import java.io.IOException;

public class LineTooLongException extends IOException {
    private final int maxLineBytes;

    public LineTooLongException(int maxLineBytes) {
        super("Line exceeds " + maxLineBytes + " bytes");
        this.maxLineBytes = maxLineBytes;
    }

    public int getMaxLineBytes() {
        return maxLineBytes;
    }
}
