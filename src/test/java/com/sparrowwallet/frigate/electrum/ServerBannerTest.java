package com.sparrowwallet.frigate.electrum;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class ServerBannerTest {
    private static final String FALLBACK = "Default banner";

    @TempDir
    Path dir;

    @Test
    public void noFileServesGeneratedBanner() {
        assertEquals(FALLBACK, ServerBanner.get(null, FALLBACK));
    }

    @Test
    public void servesFileAndRereadsWhenChanged() throws Exception {
        File banner = dir.resolve("banner.txt").toFile();
        Files.writeString(banner.toPath(), "Welcome to my server");
        banner.setLastModified(1_000_000_000_000L);
        assertEquals("Welcome to my server", ServerBanner.get(banner, FALLBACK));

        Files.writeString(banner.toPath(), "Maintenance tonight");
        banner.setLastModified(1_000_000_060_000L);
        assertEquals("Maintenance tonight", ServerBanner.get(banner, FALLBACK));
    }

    @Test
    public void unchangedFileIsServedFromCache() throws Exception {
        File banner = dir.resolve("banner.txt").toFile();
        Files.writeString(banner.toPath(), "cached");
        banner.setLastModified(1_000_000_000_000L);
        assertEquals("cached", ServerBanner.get(banner, FALLBACK));

        //same length and modification time: not re-read
        Files.writeString(banner.toPath(), "CACHED");
        banner.setLastModified(1_000_000_000_000L);
        assertEquals("cached", ServerBanner.get(banner, FALLBACK));
    }

    @Test
    public void missingOrOversizedFileServesGeneratedBanner() throws Exception {
        assertEquals(FALLBACK, ServerBanner.get(dir.resolve("missing.txt").toFile(), FALLBACK));

        File oversized = dir.resolve("big.txt").toFile();
        Files.writeString(oversized.toPath(), "x".repeat(ServerBanner.MAX_BANNER_BYTES + 1));
        assertEquals(FALLBACK, ServerBanner.get(oversized, FALLBACK));
    }
}
