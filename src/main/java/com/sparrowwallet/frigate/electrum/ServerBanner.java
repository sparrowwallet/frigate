package com.sparrowwallet.frigate.electrum;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Serves the server.banner text from an operator's banner file, read once and re-read when its modification time or size changes.
 * The file is capped in size, and if it cannot be served the generated banner is used instead.
 */
public final class ServerBanner {
    private static final Logger log = LoggerFactory.getLogger(ServerBanner.class);

    static final int MAX_BANNER_BYTES = 16 * 1024;

    private static Cached cached;

    private ServerBanner() {
    }

    /**
     * @param bannerFile the configured banner file, or null if none is configured
     * @param fallback the generated banner
     * @return the banner file's contents, or the fallback if there is no file or it cannot be served
     */
    public static synchronized String get(File bannerFile, String fallback) {
        if(bannerFile == null) {
            return fallback;
        }

        long modified = bannerFile.lastModified();
        long length = bannerFile.length();
        if(cached != null && cached.file().equals(bannerFile) && cached.modified() == modified && cached.length() == length) {
            return cached.text() != null ? cached.text() : fallback;
        }

        String text = read(bannerFile, length);
        cached = new Cached(bannerFile, modified, length, text);
        return text != null ? text : fallback;
    }

    private static String read(File bannerFile, long length) {
        if(!bannerFile.isFile()) {
            log.warn("Banner file " + bannerFile.getAbsolutePath() + " not found, serving the default banner");
            return null;
        }
        if(length > MAX_BANNER_BYTES) {
            log.warn("Banner file " + bannerFile.getAbsolutePath() + " exceeds " + MAX_BANNER_BYTES + " bytes, serving the default banner");
            return null;
        }
        try {
            return Files.readString(bannerFile.toPath(), StandardCharsets.UTF_8);
        } catch(IOException e) {
            log.warn("Could not read banner file " + bannerFile.getAbsolutePath() + ", serving the default banner: " + e.getMessage());
            return null;
        }
    }

    private record Cached(File file, long modified, long length, String text) {}
}
