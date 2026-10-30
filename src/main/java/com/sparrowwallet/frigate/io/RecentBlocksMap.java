package com.sparrowwallet.frigate.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public class RecentBlocksMap {
    private static final Logger log = LoggerFactory.getLogger(RecentBlocksMap.class);
    private static final String RECENT_BLOCKS_FILENAME = "recentblocks";

    private final TreeMap<Integer, String> data;
    private final int maxSize;

    public RecentBlocksMap(int maxSize) {
        this.maxSize = maxSize;
        this.data = new TreeMap<>(Collections.reverseOrder()); // Highest keys first
        loadFromDisk();
    }

    public synchronized String put(Integer height, String blockHash) {
        String oldValue = data.put(height, blockHash);
        enforceMaxSize();
        flush();
        return oldValue;
    }

    public synchronized String remove(Integer height) {
        String removedValue = data.remove(height);
        if(removedValue != null) {
            flush();
        }
        return removedValue;
    }

    public synchronized String get(Integer height) {
        return data.get(height);
    }

    public synchronized String getTipBlockHash() {
        if(data.isEmpty()) {
            return null;
        }
        return data.firstEntry().getValue();
    }

    public synchronized Integer getTipHeight() {
        if(data.isEmpty()) {
            return null;
        }
        return data.firstKey();
    }

    private void enforceMaxSize() {
        while(data.size() > maxSize) {
            data.pollLastEntry();
        }
    }

    private void loadFromDisk() {
        try {
            File recentBlocksFile = getRecentBlocksFile();
            if(recentBlocksFile.exists()) {
                try(Reader reader = new FileReader(recentBlocksFile)) {
                    Type mapType = new TypeToken<Map<Integer, String>>() {}.getType();
                    Map<Integer, String> loadedData = getGson().fromJson(reader, mapType);
                    if(loadedData != null) {
                        this.data.putAll(loadedData);
                        enforceMaxSize();
                    }
                }
            }
        } catch(IOException | JsonParseException e) {
            log.warn("Could not read recent blocks file, starting with an empty map: " + e.getMessage());
        }
    }

    private synchronized void flush() {
        Gson gson = getGson();
        try {
            File recentBlocksFile = getRecentBlocksFile();
            File tempFile = new File(recentBlocksFile.getParentFile(), RECENT_BLOCKS_FILENAME + ".tmp");
            if(!tempFile.exists()) {
                Storage.createOwnerOnlyFile(tempFile);
            }

            try(Writer writer = new FileWriter(tempFile)) {
                gson.toJson(this.data, writer);
                writer.flush();
            }

            //replace via rename so a crash mid-write can't leave a truncated file behind
            try {
                Files.move(tempFile.toPath(), recentBlocksFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch(AtomicMoveNotSupportedException e) {
                Files.move(tempFile.toPath(), recentBlocksFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch(IOException e) {
            //Ignore
        }
    }

    private Gson getGson() {
        return new GsonBuilder().setPrettyPrinting().create();
    }

    private static File getRecentBlocksFile() {
        File sparrowDir = Storage.getFrigateDir();
        return new File(sparrowDir, RECENT_BLOCKS_FILENAME);
    }
}
