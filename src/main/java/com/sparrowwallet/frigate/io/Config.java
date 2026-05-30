package com.sparrowwallet.frigate.io;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import com.sparrowwallet.frigate.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class Config {
    private static final Logger log = LoggerFactory.getLogger(Config.class);

    public static final String TOML_CONFIG_FILENAME = "config.toml";
    public static final String JSON_CONFIG_FILENAME = "config";

    private CoreConfig core;
    private IndexConfig index;
    private ScanConfig scan;
    private ServerConfig server;
    private DatabaseConfig database;

    private static Config INSTANCE;

    private static final TomlMapper tomlMapper = TomlMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    public Config() {
        core = new CoreConfig();
        index = new IndexConfig();
        scan = new ScanConfig();
        server = new ServerConfig();
        database = new DatabaseConfig();
    }

    private static File getTomlConfigFile() {
        return new File(Storage.getFrigateDir(), TOML_CONFIG_FILENAME);
    }

    private static File getJsonConfigFile() {
        return new File(Storage.getFrigateDir(), JSON_CONFIG_FILENAME);
    }

    private static Config load() {
        File tomlFile = getTomlConfigFile();
        File jsonFile = getJsonConfigFile();

        if(tomlFile.exists()) {
            try {
                Config config = tomlMapper.readValue(tomlFile, Config.class);
                if(config != null) {
                    return config;
                }
            } catch(Exception e) {
                log.error("Error reading " + tomlFile.getAbsolutePath(), e);
            }
        } else if(jsonFile.exists()) {
            try {
                Config config = migrateFromJson(jsonFile);
                if(config != null) {
                    saveToml(config, tomlFile);
                    File backupFile = new File(jsonFile.getPath() + ".bak");
                    jsonFile.renameTo(backupFile);
                    log.info("Migrated config from JSON to TOML (backup: {})", backupFile.getName());
                    return config;
                }
            } catch(Exception e) {
                log.error("Error migrating " + jsonFile.getAbsolutePath(), e);
            }
        } else {
            try {
                writeDefaultConfig(tomlFile);
            } catch(Exception e) {
                log.error("Error writing default config", e);
            }
        }

        return new Config();
    }

    private static Config migrateFromJson(File jsonFile) throws IOException {
        ObjectMapper jsonMapper = new ObjectMapper();
        jsonMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        JsonNode root = jsonMapper.readTree(jsonFile);

        Config config = new Config();

        if(root.has("coreServer")) {
            config.getCore().setServer(root.get("coreServer").asText());
        }
        if(root.has("coreAuthType")) {
            config.getCore().setAuthType(root.get("coreAuthType").asText());
        }
        if(root.has("coreDataDir")) {
            config.getCore().setDataDir(root.get("coreDataDir").asText());
        }
        if(root.has("coreAuth")) {
            config.getCore().setAuth(root.get("coreAuth").asText());
        }
        if(root.has("startIndexing")) {
            config.getCore().setConnect(root.get("startIndexing").asBoolean());
        }
        if(root.has("indexStartHeight")) {
            config.getIndex().setStartHeight(root.get("indexStartHeight").asInt());
        }
        if(root.has("scriptPubKeyCacheSize")) {
            int oldSize = root.get("scriptPubKeyCacheSize").asInt();
            config.getIndex().setCacheSize(formatCacheSize(oldSize));
        }

        if(root.has("batchSize")) {
            config.getScan().setBatchSize(root.get("batchSize").asInt());
        }
        if(root.has("computeBackend")) {
            config.getScan().setComputeBackend(root.get("computeBackend").asText());
        }
        if(root.has("dbThreads")) {
            config.getScan().setDbThreads(root.get("dbThreads").asInt());
        }

        if(root.has("backendElectrumServer")) {
            config.getServer().setBackendElectrumServer(root.get("backendElectrumServer").asText());
        }

        if(root.has("dbUrl")) {
            config.getDatabase().setUrl(root.get("dbUrl").asText());
        }
        if(root.has("readDbUrls")) {
            List<String> urls = new java.util.ArrayList<>();
            root.get("readDbUrls").forEach(node -> urls.add(node.asText()));
            config.getDatabase().setReadUrls(urls);
        }

        return config;
    }

    private static String formatCacheSize(int size) {
        if(size >= 1_000_000 && size % 1_000_000 == 0) {
            return (size / 1_000_000) + "M";
        } else if(size >= 1_000 && size % 1_000 == 0) {
            return (size / 1_000) + "K";
        }
        return String.valueOf(size);
    }

    static int parseCacheSize(String value) {
        if(value == null || value.isEmpty()) {
            return 10_000_000;
        }
        String s = value.trim().toUpperCase();
        if(s.endsWith("M")) {
            return (int) (Double.parseDouble(s.substring(0, s.length() - 1)) * 1_000_000);
        } else if(s.endsWith("K")) {
            return (int) (Double.parseDouble(s.substring(0, s.length() - 1)) * 1_000);
        }
        return Integer.parseInt(s);
    }

    private static void writeDefaultConfig(File tomlFile) {
        try(InputStream is = Config.class.getResourceAsStream("/config.toml.default")) {
            if(is != null) {
                Storage.createOwnerOnlyFile(tomlFile);
                Files.copy(is, tomlFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch(IOException e) {
            log.error("Error writing default config", e);
        }
    }

    private static void saveToml(Config config, File tomlFile) {
        try {
            if(!tomlFile.exists()) {
                Storage.createOwnerOnlyFile(tomlFile);
            }
            tomlMapper.writeValue(tomlFile, config);
        } catch(IOException e) {
            log.error("Error writing config", e);
        }
    }

    public static synchronized Config get() {
        if(INSTANCE == null) {
            INSTANCE = load();
            INSTANCE.getServer().getAdvertisedHosts();
        }
        return INSTANCE;
    }

    public CoreConfig getCore() {
        if(core == null) {
            core = new CoreConfig();
        }
        return core;
    }

    public IndexConfig getIndex() {
        if(index == null) {
            index = new IndexConfig();
        }
        return index;
    }

    public ScanConfig getScan() {
        if(scan == null) {
            scan = new ScanConfig();
        }
        return scan;
    }

    public ServerConfig getServer() {
        if(server == null) {
            server = new ServerConfig();
        }
        return server;
    }

    public DatabaseConfig getDatabase() {
        if(database == null) {
            database = new DatabaseConfig();
        }
        return database;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CoreConfig {
        public static final int DEFAULT_RPC_REQUEST_TIMEOUT_SECONDS = 60;
        public static final int DEFAULT_RPC_BATCH_SIZE = 100;

        private Boolean connect;
        private String server;
        private String authType;
        private String dataDir;
        private String auth;
        private String zmqSequenceEndpoint;
        private Integer rpcRequestTimeoutSeconds;
        private Integer rpcBatchSize;

        public Boolean getConnect() {
            return connect;
        }

        public void setConnect(Boolean connect) {
            this.connect = connect;
        }

        @JsonIgnore
        public boolean shouldConnect() {
            return connect == null || connect;
        }

        public String getServer() {
            return server;
        }

        public void setServer(String server) {
            this.server = server;
        }

        public String getAuthType() {
            return authType;
        }

        public void setAuthType(String authType) {
            this.authType = authType;
        }

        @JsonIgnore
        public CoreAuthType getAuthTypeEnum() {
            if(authType == null) {
                return null;
            }
            try {
                return CoreAuthType.valueOf(authType);
            } catch(Exception e) {
                return null;
            }
        }

        public String getDataDir() {
            return dataDir;
        }

        public void setDataDir(String dataDir) {
            this.dataDir = dataDir;
        }

        @JsonIgnore
        public File getDataDirFile() {
            return dataDir != null ? new File(dataDir) : null;
        }

        public String getAuth() {
            return auth;
        }

        public void setAuth(String auth) {
            this.auth = auth;
        }

        @JsonIgnore
        public Server getServerObj() {
            return server != null ? Server.fromString(server) : null;
        }

        public String getZmqSequenceEndpoint() {
            return zmqSequenceEndpoint;
        }

        public void setZmqSequenceEndpoint(String zmqSequenceEndpoint) {
            this.zmqSequenceEndpoint = zmqSequenceEndpoint;
        }

        public Integer getRpcRequestTimeoutSeconds() {
            return rpcRequestTimeoutSeconds;
        }

        public void setRpcRequestTimeoutSeconds(Integer rpcRequestTimeoutSeconds) {
            this.rpcRequestTimeoutSeconds = rpcRequestTimeoutSeconds;
        }

        @JsonIgnore
        public int getRpcRequestTimeoutMillis() {
            int seconds = rpcRequestTimeoutSeconds != null ? rpcRequestTimeoutSeconds : DEFAULT_RPC_REQUEST_TIMEOUT_SECONDS;
            return seconds * 1000;
        }

        public Integer getRpcBatchSize() {
            return rpcBatchSize;
        }

        public void setRpcBatchSize(Integer rpcBatchSize) {
            this.rpcBatchSize = rpcBatchSize;
        }

        @JsonIgnore
        public int getRpcBatchSizeValue() {
            return rpcBatchSize != null && rpcBatchSize > 0 ? rpcBatchSize : DEFAULT_RPC_BATCH_SIZE;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class IndexConfig {
        private Integer startHeight;
        private String cacheSize;

        public Integer getStartHeight() {
            return startHeight;
        }

        public void setStartHeight(Integer startHeight) {
            this.startHeight = startHeight;
        }

        public String getCacheSize() {
            return cacheSize;
        }

        public void setCacheSize(String cacheSize) {
            this.cacheSize = cacheSize;
        }

        @JsonIgnore
        public int getCacheSizeEntries() {
            return Config.parseCacheSize(cacheSize);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ScanConfig {
        public static final int DEFAULT_BATCH_SIZE = 300_000;
        public static final int DEFAULT_MAX_LABELS = 10;
        public static final int DEFAULT_MAX_SUBSCRIPTIONS = 100;

        private Integer batchSize;
        private String computeBackend;
        private Integer dbThreads;
        private String memoryLimit;
        private Integer maxLabels;
        private Integer maxSubscriptions;
        private Boolean metricsEnabled;

        public int getBatchSize() {
            return batchSize != null ? batchSize : DEFAULT_BATCH_SIZE;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getMaxLabels() {
            return maxLabels != null ? maxLabels : DEFAULT_MAX_LABELS;
        }

        public void setMaxLabels(Integer maxLabels) {
            this.maxLabels = maxLabels;
        }

        public int getMaxSubscriptions() {
            return maxSubscriptions != null ? maxSubscriptions : DEFAULT_MAX_SUBSCRIPTIONS;
        }

        public void setMaxSubscriptions(Integer maxSubscriptions) {
            this.maxSubscriptions = maxSubscriptions;
        }

        @JsonIgnore
        public ComputeBackend getComputeBackendEnum() {
            if(computeBackend == null) {
                return ComputeBackend.AUTO;
            }
            try {
                return ComputeBackend.valueOf(computeBackend);
            } catch(Exception e) {
                return ComputeBackend.AUTO;
            }
        }

        public String getComputeBackend() {
            return computeBackend;
        }

        public void setComputeBackend(String computeBackend) {
            this.computeBackend = computeBackend;
        }

        public Integer getDbThreads() {
            return dbThreads;
        }

        public void setDbThreads(Integer dbThreads) {
            this.dbThreads = dbThreads;
        }

        public String getMemoryLimit() {
            return memoryLimit;
        }

        public void setMemoryLimit(String memoryLimit) {
            this.memoryLimit = memoryLimit;
        }

        public boolean isMetricsEnabled() {
            return metricsEnabled == null || metricsEnabled;
        }

        public void setMetricsEnabled(Boolean metricsEnabled) {
            this.metricsEnabled = metricsEnabled;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ServerConfig {
        private List<String> host;
        private String tcp;
        private String ssl;
        private String sslCert;
        private String sslKey;
        private String backendElectrumServer;

        @JsonIgnore
        private List<Server> advertisedHostsCache;

        public Object getHost() {
            if(host == null || host.isEmpty()) {
                return null;
            }
            return host.size() == 1 ? host.getFirst() : host;
        }

        @JsonSetter("host")
        public void setHost(JsonNode node) {
            if(node == null || node.isNull()) {
                this.host = null;
                return;
            }

            List<String> entries = new ArrayList<>();
            if(node.isTextual()) {
                String value = node.asText();
                if(!value.isEmpty()) {
                    entries.add(value);
                }
            } else if(node.isArray()) {
                for(JsonNode item : node) {
                    if(!item.isTextual()) {
                        throw new ConfigurationException("Each entry in 'host' under [server] must be a string (got: " + item + ")");
                    }
                    String value = item.asText();
                    if(!value.isEmpty()) {
                        entries.add(value);
                    }
                }
            } else {
                throw new ConfigurationException("'host' under [server] must be a string or array of strings (got: " + node + ")");
            }

            this.host = entries.isEmpty() ? null : entries;
        }

        @JsonIgnore
        public List<Server> getAdvertisedHosts() {
            if(advertisedHostsCache != null) {
                return advertisedHostsCache;
            }
            if(host == null || host.isEmpty()) {
                advertisedHostsCache = List.of();
                return advertisedHostsCache;
            }

            List<Server> result = new ArrayList<>();
            for(String entry : host) {
                Protocol protocol = Protocol.getProtocol(entry);
                if(protocol == null) {
                    Server tcpBind = getTcpServer();
                    if(tcpBind != null) {
                        result.add(Server.fromString(Protocol.TCP.toUrlString(entry, tcpBind.getHostAndPort().getPort())));
                    }
                    Server sslBind = getSslServer();
                    if(sslBind != null) {
                        result.add(Server.fromString(Protocol.SSL.toUrlString(entry, sslBind.getHostAndPort().getPort())));
                    }
                } else if(protocol == Protocol.TCP || protocol == Protocol.SSL) {
                    result.add(parseListener("host", entry, protocol));
                } else {
                    throw new ConfigurationException("Host advertisement '" + entry + "' under [server] must use tcp:// or ssl:// scheme");
                }
            }

            advertisedHostsCache = List.copyOf(result);
            return advertisedHostsCache;
        }

        public String getTcp() {
            return tcp;
        }

        public void setTcp(String tcp) {
            this.tcp = tcp;
        }

        @JsonSetter("port")
        public void setLegacyPort(Integer port) {
            if(port != null && this.tcp == null) {
                this.tcp = "tcp://0.0.0.0:" + port;
            }
        }

        public String getSsl() {
            return ssl;
        }

        public void setSsl(String ssl) {
            this.ssl = ssl;
        }

        @JsonIgnore
        public Server getTcpServer() {
            if(tcp == null && ssl == null) {
                return parseListener("tcp", "tcp://0.0.0.0:" + Protocol.TCP.getDefaultPort(), Protocol.TCP);
            }
            if(tcp == null || tcp.isEmpty()) {
                return null;
            }
            return parseListener("tcp", tcp, Protocol.TCP);
        }

        @JsonIgnore
        public Server getSslServer() {
            if(ssl == null || ssl.isEmpty()) {
                return null;
            }
            return parseListener("ssl", ssl, Protocol.SSL);
        }

        @JsonIgnore
        public boolean isTcpEnabled() {
            return getTcpServer() != null;
        }

        @JsonIgnore
        public boolean isSslEnabled() {
            return getSslServer() != null;
        }

        private static Server parseListener(String key, String url, Protocol expected) {
            Server server;
            try {
                server = Server.fromString(url);
            } catch(IllegalArgumentException e) {
                throw new com.sparrowwallet.frigate.ConfigurationException("Invalid " + key + " listener URL '" + url + "' under [server] in config.toml: " + e.getMessage(), e);
            }

            if(server.getProtocol() != expected) {
                throw new com.sparrowwallet.frigate.ConfigurationException("Listener '" + key + "' must use the " + expected.toUrlString() + " scheme (got '" + url + "')");
            }

            if(!server.getHostAndPort().hasPort()) {
                throw new com.sparrowwallet.frigate.ConfigurationException("Listener '" + key + "' must specify an explicit port: '" + url + "'");
            }

            return server;
        }

        public static final String DEFAULT_SSL_CERT = "cert.pem";
        public static final String DEFAULT_SSL_KEY = "key.pem";

        public String getSslCert() {
            return sslCert;
        }

        public void setSslCert(String sslCert) {
            this.sslCert = sslCert;
        }

        @JsonIgnore
        public File getSslCertFile() {
            return resolveFrigateDirPath(sslCert, DEFAULT_SSL_CERT);
        }

        public String getSslKey() {
            return sslKey;
        }

        public void setSslKey(String sslKey) {
            this.sslKey = sslKey;
        }

        @JsonIgnore
        public File getSslKeyFile() {
            return resolveFrigateDirPath(sslKey, DEFAULT_SSL_KEY);
        }

        private static File resolveFrigateDirPath(String configured, String defaultName) {
            String value = (configured == null || configured.isEmpty()) ? defaultName : configured;
            File file = new File(value);
            return file.isAbsolute() ? file : new File(Storage.getFrigateDir(), value);
        }

        public String getBackendElectrumServer() {
            return backendElectrumServer;
        }

        public void setBackendElectrumServer(String backendElectrumServer) {
            this.backendElectrumServer = backendElectrumServer;
        }

        @JsonIgnore
        public Server getBackendElectrumServerObj() {
            return backendElectrumServer != null ? Server.fromString(backendElectrumServer) : null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DatabaseConfig {
        private String url;
        private List<String> readUrls;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public List<String> getReadUrls() {
            return readUrls;
        }

        public void setReadUrls(List<String> readUrls) {
            this.readUrls = readUrls;
        }
    }
}
