package com.sparrowwallet.frigate.bitcoind.reader;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Memory-maps blk?????.dat and rev?????.dat files on demand, caching the mappings
 * for reuse across concurrent readers. Thread-safe.
 *
 * <p>Uses an LRU eviction strategy to bound the number of mapped files. Each mapping
 * has its own {@link Arena} so evicted mappings release their OS-level mapping immediately.
 * The most recent block/rev file (highest file number for each type) is excluded from
 * caching because Bitcoin Core may still be appending to it — reads to those files
 * return null, signaling callers to fall back to RandomAccessFile.
 */
public class MappedBlockFiles implements Closeable {
    // Each blk/rev file is ~134 MB. 16 files ≈ ~2 GB virtual address space, covering
    // ~8 block files + 8 rev files (consecutive blocks are in the same or adjacent files).
    private static final int MAX_CACHED_FILES = 16;

    private final Path blocksDir;

    private record MappedFile(Arena arena, MemorySegment segment) implements Closeable {
        @Override
        public void close() {
            arena.close();
        }
    }

    // LRU cache: eldest entries are evicted and their Arena closed when size exceeds MAX_CACHED_FILES.
    // All access synchronized on 'this'.
    private final LinkedHashMap<String, MappedFile> mappedFiles = new LinkedHashMap<>(MAX_CACHED_FILES, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, MappedFile> eldest) {
            if(size() > MAX_CACHED_FILES) {
                eldest.getValue().close();
                return true;
            }
            return false;
        }
    };

    // Highest file number seen for blk and rev files. Reads to these files are
    // excluded from caching because Bitcoin Core may still be appending to them.
    private volatile int lastBlkFileNumber = -1;
    private volatile int lastRevFileNumber = -1;

    public MappedBlockFiles(Path blocksDir) {
        this.blocksDir = blocksDir;
    }

    /**
     * Read bytes from a block or undo file at the given offset.
     * Returns null if the file should not be memory-mapped (active file that may
     * still be growing), signaling the caller to fall back to RandomAccessFile.
     */
    public byte[] read(String fileName, long offset, int length) throws IOException {
        if(isActiveFile(fileName)) {
            return null;
        }

        MemorySegment segment;
        synchronized(this) {
            MappedFile mapped = mappedFiles.get(fileName);
            if(mapped == null) {
                mapped = mapFile(fileName);
                mappedFiles.put(fileName, mapped);
            }
            segment = mapped.segment();
        }

        if(offset + length > segment.byteSize()) {
            throw new IOException("Read beyond mapped file bounds: " + fileName + " offset=" + offset + " length=" + length + " fileSize=" + segment.byteSize());
        }

        byte[] data = new byte[length];
        MemorySegment.copy(segment, offset, MemorySegment.ofArray(data), 0, length);
        return data;
    }

    private MappedFile mapFile(String fileName) throws IOException {
        Path file = blocksDir.resolve(fileName);
        Arena arena = Arena.ofShared();
        try(FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            MemorySegment segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);
            return new MappedFile(arena, segment);
        } catch(IOException e) {
            arena.close();
            throw e;
        }
    }

    /**
     * Check if this is the most recent (possibly still growing) file for its type.
     * Tracks the highest file number seen and excludes it from caching.
     */
    private boolean isActiveFile(String fileName) {
        int fileNumber = Integer.parseInt(fileName.substring(3, 8));
        boolean isBlk = fileName.startsWith("blk");

        if(isBlk) {
            if(fileNumber > lastBlkFileNumber) {
                lastBlkFileNumber = fileNumber;
            }
            return fileNumber == lastBlkFileNumber;
        } else {
            if(fileNumber > lastRevFileNumber) {
                lastRevFileNumber = fileNumber;
            }
            return fileNumber == lastRevFileNumber;
        }
    }

    @Override
    public void close() {
        synchronized(this) {
            for(MappedFile mapped : mappedFiles.values()) {
                mapped.close();
            }
            mappedFiles.clear();
        }
    }
}
