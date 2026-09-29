package com.sparrowwallet.frigate.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads newline-delimited lines from a byte stream, bounding each line's length in bytes so a peer cannot exhaust memory with a
 * line that never ends. Lines are framed on the raw bytes and decoded as UTF-8 only once complete, so the bound is a true byte
 * bound (a char-based bound would allow several times as many bytes of multi-byte UTF-8). A trailing \r is stripped.
 *
 * The reader does its own buffering, so the stream should not be buffered. A partly read line is kept when a read throws, so
 * a socket read timeout part way through a line (to check for an idle client, for example) loses nothing, and the next
 * readLine() continues the line. After a LineTooLongException the stream is left part way through the line and the connection
 * should be closed.
 */
public class BoundedLineReader {
    private static final int BUFFER_SIZE = 8192;
    private static final int RETAINED_LINE_CAPACITY = 64 * 1024;

    private final InputStream in;
    private final int maxLineBytes;
    private final byte[] buffer = new byte[BUFFER_SIZE];
    private int position;
    private int limit;
    private ByteArrayOutputStream line = new ByteArrayOutputStream(256);

    /**
     * @param maxLineBytes the maximum length of a line in bytes, excluding its terminating \n or \r\n
     */
    public BoundedLineReader(InputStream in, int maxLineBytes) {
        this.in = in;
        this.maxLineBytes = maxLineBytes;
    }

    /**
     * @return the next line without its terminator, the final unterminated line at the end of the stream, or null at the end of the stream
     * @throws LineTooLongException if the line exceeds maxLineBytes
     */
    public String readLine() throws IOException {
        while(true) {
            for(int i = position; i < limit; i++) {
                if(buffer[i] == '\n') {
                    append(position, i - position);
                    position = i + 1;
                    return takeLine(true);
                }
            }

            append(position, limit - position);
            position = limit;

            int read = in.read(buffer);
            if(read == -1) {
                return line.size() == 0 ? null : takeLine(false);
            }
            position = 0;
            limit = read;
        }
    }

    private void append(int offset, int length) throws LineTooLongException {
        //one extra byte is allowed while accumulating, as it may be the \r of a \r\n terminator
        if(line.size() + length > maxLineBytes + 1) {
            discardLine();
            throw new LineTooLongException(maxLineBytes);
        }
        line.write(buffer, offset, length);
    }

    private String takeLine(boolean terminated) throws LineTooLongException {
        byte[] bytes = line.toByteArray();
        int length = terminated && bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
        discardLine();
        if(length > maxLineBytes) {
            throw new LineTooLongException(maxLineBytes);
        }
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    private void discardLine() {
        if(line.size() > RETAINED_LINE_CAPACITY) {
            //do not keep a large buffer allocated for the rest of the connection after one long line
            line = new ByteArrayOutputStream(256);
        } else {
            line.reset();
        }
    }
}
