package com.sparrowwallet.frigate.io;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.*;

public class BoundedLineReaderTest {
    private static final byte[] READ_TIMEOUT = new byte[0];

    private static BoundedLineReader reader(String text, int maxLineBytes) {
        return new BoundedLineReader(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), maxLineBytes);
    }

    @Test
    public void readsLines() throws IOException {
        BoundedLineReader reader = reader("first\nsecond\r\n\nlast", 100);

        assertEquals("first", reader.readLine());
        assertEquals("second", reader.readLine());
        assertEquals("", reader.readLine());
        assertEquals("last", reader.readLine());
        assertNull(reader.readLine());
        assertNull(reader.readLine());
    }

    @Test
    public void lineAtLimitIsAccepted() throws IOException {
        String line = "x".repeat(10);
        BoundedLineReader reader = reader(line + "\n" + line + "\r\n", 10);

        assertEquals(line, reader.readLine());
        //the \r of a \r\n terminator does not count towards the limit
        assertEquals(line, reader.readLine());
    }

    @Test
    public void lineOverLimitThrows() throws IOException {
        LineTooLongException e = assertThrows(LineTooLongException.class, () -> reader("x".repeat(11) + "\n", 10).readLine());
        assertEquals(10, e.getMaxLineBytes());
        //a \r that is not part of the terminator counts
        assertThrows(LineTooLongException.class, () -> reader("x".repeat(10) + "\ry\n", 10).readLine());
        //as does an unterminated line at the end of the stream
        assertThrows(LineTooLongException.class, () -> reader("x".repeat(11), 10).readLine());
    }

    @Test
    public void unterminatedLineIsBoundedBeforeEndOfStream() {
        //an endless line without a newline is rejected once past the limit, not buffered until the stream ends
        InputStream endless = new InputStream() {
            @Override
            public int read() {
                return 'x';
            }
        };

        assertThrows(LineTooLongException.class, () -> new BoundedLineReader(endless, 100_000).readLine());
    }

    @Test
    public void limitCountsBytesNotChars() throws IOException {
        //"é" is two bytes in UTF-8, so five of them are ten bytes
        String accented = "é".repeat(5);
        assertEquals(accented, reader(accented + "\n", 10).readLine());
        assertThrows(LineTooLongException.class, () -> reader(accented + "\n", 9).readLine());
    }

    @Test
    public void multiByteCharacterSplitAcrossReadsIsDecoded() throws IOException {
        byte[] bytes = "aé€😀b\n".getBytes(StandardCharsets.UTF_8);
        Deque<byte[]> chunks = new ArrayDeque<>();
        for(byte b : bytes) {
            chunks.add(new byte[] {b});
        }

        assertEquals("aé€😀b", new BoundedLineReader(new ChunkedInputStream(chunks), 100).readLine());
    }

    @Test
    public void linesSpanningBufferBoundaries() throws IOException {
        String longLine = "y".repeat(20_000);
        BoundedLineReader reader = reader("short\n" + longLine + "\nafter\n", 30_000);

        assertEquals("short", reader.readLine());
        assertEquals(longLine, reader.readLine());
        assertEquals("after", reader.readLine());
    }

    @Test
    public void readTimeoutPartWayThroughLineKeepsPartialLine() throws IOException {
        Deque<byte[]> chunks = new ArrayDeque<>();
        chunks.add("{\"id\":1,".getBytes(StandardCharsets.UTF_8));
        chunks.add(READ_TIMEOUT);
        chunks.add("\"method\":\"server.ping\"}\nnext\n".getBytes(StandardCharsets.UTF_8));
        BoundedLineReader reader = new BoundedLineReader(new ChunkedInputStream(chunks), 100);

        assertThrows(SocketTimeoutException.class, reader::readLine);
        assertEquals("{\"id\":1,\"method\":\"server.ping\"}", reader.readLine());
        assertEquals("next", reader.readLine());
    }

    /** Returns each chunk from one read, and READ_TIMEOUT as a socket read timeout. */
    private static class ChunkedInputStream extends InputStream {
        private final Deque<byte[]> chunks;

        ChunkedInputStream(Deque<byte[]> chunks) {
            this.chunks = chunks;
        }

        @Override
        public int read() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if(chunks.isEmpty()) {
                return -1;
            }
            byte[] chunk = chunks.poll();
            if(chunk == READ_TIMEOUT) {
                throw new SocketTimeoutException("Read timed out");
            }
            System.arraycopy(chunk, 0, b, off, chunk.length);
            return chunk.length;
        }
    }
}
