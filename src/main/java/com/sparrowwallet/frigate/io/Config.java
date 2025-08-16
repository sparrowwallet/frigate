package com.sparrowwallet.frigate.io;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Type;

public class Config {
    private static final Logger log = LoggerFactory.getLogger(Config.class);

    public static final String CONFIG_FILENAME = "config";

    private Server coreServer;
    private CoreAuthType coreAuthType;
    private File coreDataDir;
    private String coreAuth;

    private static Config INSTANCE;

    private static Gson getGson() {
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(File.class, new FileSerializer());
        gsonBuilder.registerTypeAdapter(File.class, new FileDeserializer());
        gsonBuilder.registerTypeAdapter(Server.class, new ServerSerializer());
        gsonBuilder.registerTypeAdapter(Server.class, new ServerDeserializer());
        return gsonBuilder.setPrettyPrinting().disableHtmlEscaping().create();
    }

    private static File getConfigFile() {
        File sparrowDir = Storage.getFrigateDir();
        return new File(sparrowDir, CONFIG_FILENAME);
    }

    private static Config load() {
        File configFile = getConfigFile();
        if(configFile.exists()) {
            try {
                Reader reader = new FileReader(configFile);
                Config config = getGson().fromJson(reader, Config.class);
                reader.close();

                if(config != null) {
                    return config;
                }
            } catch(Exception e) {
                log.error("Error opening " + configFile.getAbsolutePath(), e);
                //Ignore and assume no config
            }
        }

        return new Config();
    }

    public static synchronized Config get() {
        if(INSTANCE == null) {
            INSTANCE = load();
        }

        return INSTANCE;
    }

    public Server getCoreServer() {
        return coreServer;
    }

    public void setCoreServer(Server coreServer) {
        this.coreServer = coreServer;
        flush();
    }

    public CoreAuthType getCoreAuthType() {
        return coreAuthType;
    }

    public void setCoreAuthType(CoreAuthType coreAuthType) {
        this.coreAuthType = coreAuthType;
        flush();
    }

    public File getCoreDataDir() {
        return coreDataDir;
    }

    public void setCoreDataDir(File coreDataDir) {
        this.coreDataDir = coreDataDir;
        flush();
    }

    public String getCoreAuth() {
        return coreAuth;
    }

    public void setCoreAuth(String coreAuth) {
        this.coreAuth = coreAuth;
        flush();
    }

    private synchronized void flush() {
        Gson gson = getGson();
        try {
            File configFile = getConfigFile();
            if(!configFile.exists()) {
                Storage.createOwnerOnlyFile(configFile);
            }

            Writer writer = new FileWriter(configFile);
            gson.toJson(this, writer);
            writer.flush();
            writer.close();
        } catch (IOException e) {
            //Ignore
        }
    }

    private static class FileSerializer implements JsonSerializer<File> {
        @Override
        public JsonElement serialize(File src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(src.getAbsolutePath());
        }
    }

    private static class FileDeserializer implements JsonDeserializer<File> {
        @Override
        public File deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            return new File(json.getAsJsonPrimitive().getAsString());
        }
    }

    private static class ServerSerializer implements JsonSerializer<Server> {
        @Override
        public JsonElement serialize(Server src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(src.toString());
        }
    }

    private static class ServerDeserializer implements JsonDeserializer<Server> {
        @Override
        public Server deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            return Server.fromString(json.getAsJsonPrimitive().getAsString());
        }
    }
}

