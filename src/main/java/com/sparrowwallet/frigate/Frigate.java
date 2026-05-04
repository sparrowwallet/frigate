package com.sparrowwallet.frigate;

import com.beust.jcommander.JCommander;
import com.google.common.eventbus.EventBus;
import com.sparrowwallet.drongo.Drongo;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.frigate.electrum.ElectrumServerRunnable;
import com.sparrowwallet.frigate.bitcoind.BitcoindClient;
import com.sparrowwallet.frigate.index.Index;
import com.sparrowwallet.frigate.index.IndexQuerier;
import com.sparrowwallet.drongo.OsType;
import com.sparrowwallet.frigate.io.Config;
import com.sparrowwallet.frigate.io.Storage;
import com.github.arteam.simplejsonrpc.client.exception.JsonRpcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.BindException;
import java.net.ConnectException;
import java.sql.SQLException;
import java.util.Locale;

public class Frigate {
    public static final String SERVER_NAME = "Frigate";
    public static final String SERVER_VERSION = "1.4.1";
    public static final String APP_HOME_PROPERTY = "frigate.home";
    public static final String NETWORK_ENV_PROPERTY = "FRIGATE_NETWORK";
    private static final int MAINNET_TAPROOT_ACTIVATION_HEIGHT = 709632;
    private static final int TESTNET_TAPROOT_ACTIVATION_HEIGHT = 0;

    private static final EventBus EVENT_BUS = new EventBus();

    private Index blocksIndex;
    private Index mempoolIndex;
    private BitcoindClient bitcoindClient;
    private ElectrumServerRunnable electrumServer;

    private boolean running;

    private static Object trayManager;

    public void start() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));

        Config config = Config.get();

        Integer startHeight = config.getIndex().getStartHeight();
        if(startHeight == null) {
            startHeight = Network.get() == Network.MAINNET ? MAINNET_TAPROOT_ACTIVATION_HEIGHT : TESTNET_TAPROOT_ACTIVATION_HEIGHT;
        }

        int batchSize = config.getScan().getBatchSize();

        blocksIndex = new Index(startHeight, false, batchSize);
        mempoolIndex = new Index(0, true, batchSize);

        if(config.getCore().shouldConnect()) {
            bitcoindClient = new BitcoindClient(blocksIndex, mempoolIndex);
            bitcoindClient.initialize();
        }

        electrumServer = new ElectrumServerRunnable(bitcoindClient, new IndexQuerier(blocksIndex, mempoolIndex), config.getServer().getTcpPort());
        Thread electrumServerThread = new Thread(electrumServer, "Frigate Electrum Server");
        electrumServerThread.setDaemon(false);
        electrumServerThread.start();

        running = true;
    }

    public boolean isRunning() {
        return running;
    }

    public void stop() {
        if(blocksIndex != null) {
            blocksIndex.close();
        }
        if(mempoolIndex != null) {
            mempoolIndex.close();
        }
        if(bitcoindClient != null) {
            bitcoindClient.stop();
        }
        if(electrumServer != null) {
            electrumServer.stop();
        }

        running = false;
    }

    public static EventBus getEventBus() {
        return EVENT_BUS;
    }

    private static Logger getLogger() {
        return LoggerFactory.getLogger(Frigate.class);
    }

    private static void initTray() {
        if(com.sparrowwallet.frigate.control.TrayManager.isSupported()) {
            com.sparrowwallet.frigate.control.TrayManager mgr = new com.sparrowwallet.frigate.control.TrayManager();
            EVENT_BUS.register(mgr);
            trayManager = mgr;
        }
    }

    public static void main(String[] argv) {
        Args args = new Args();
        JCommander jCommander = JCommander.newBuilder().addObject(args).programName(SERVER_NAME.toLowerCase(Locale.ROOT)).acceptUnknownOptions(true).build();
        jCommander.parse(argv);
        if(args.help) {
            jCommander.usage();
            System.exit(0);
        }

        if(args.version) {
            System.out.println(SERVER_NAME + " " + SERVER_VERSION);
            System.exit(0);
        }

        if(args.level != null) {
            Drongo.setRootLogLevel(args.level);
        }

        if(args.dir != null) {
            System.setProperty(APP_HOME_PROPERTY, args.dir);
            getLogger().info("Using configured Frigate home folder of " + args.dir);
        }

        if(args.network != null) {
            Network.set(args.network);
        } else {
            String envNetwork = System.getenv(NETWORK_ENV_PROPERTY);
            if(envNetwork != null) {
                try {
                    Network.set(Network.valueOf(envNetwork.toUpperCase(Locale.ROOT)));
                } catch(Exception e) {
                    getLogger().warn("Invalid " + NETWORK_ENV_PROPERTY + " property: " + envNetwork);
                }
            }
        }

        File testnetFlag = new File(Storage.getFrigateHome(), "network-" + Network.TESTNET.getName());
        if(testnetFlag.exists()) {
            Network.set(Network.TESTNET);
        }

        File testnet4Flag = new File(Storage.getFrigateHome(), "network-" + Network.TESTNET4.getName());
        if(testnet4Flag.exists()) {
            Network.set(Network.TESTNET4);
        }

        File signetFlag = new File(Storage.getFrigateHome(), "network-" + Network.SIGNET.getName());
        if(signetFlag.exists()) {
            Network.set(Network.SIGNET);
        }

        if(Network.get() != Network.MAINNET) {
            getLogger().info("Using " + Network.get() + " configuration");
        }

        try {
            if(OsType.getCurrent() == OsType.MACOS) {
                initTray();
            }

            Frigate frigate = new Frigate();
            frigate.start();
        } catch(Exception e) {
            String message = getOperationalErrorMessage(e);
            if(message != null) {
                getLogger().error(message);
            } else {
                getLogger().error("Fatal error", e);
            }
            System.exit(1);
        }
    }

    private static String getOperationalErrorMessage(Exception e) {
        String configExceptionMessage = null;
        Throwable current = e;
        while(current != null) {
            if(current instanceof ConnectException) {
                String server = Config.get().getCore().getServer();
                if(server == null) server = "http://127.0.0.1:8332";
                return "Cannot connect to Bitcoin Core at " + server + ". Ensure Bitcoin Core is running and the server URL in config.toml is correct, or set connect = false under [core].";
            }
            if(current instanceof BindException) {
                int port = Config.get().getServer().getTcpPort();
                return "Port " + port + " is already in use. Another Frigate instance may be running, or change tcpPort under [server] in config.toml.";
            }
            if(current instanceof IOException ioe && ioe.getMessage() != null) {
                String msg = ioe.getMessage();
                if(msg.contains("Cannot find Bitcoin Core cookie file")) {
                    return msg + ". Ensure Bitcoin Core is running, or set connect = false under [core] in config.toml.";
                }
                if(msg.contains("authentication failed")) {
                    return "Bitcoin Core authentication failed. Check authType, dataDir, and auth under [core] in config.toml, or set connect = false.";
                }
            }
            if(current instanceof SQLException sql && sql.getMessage() != null) {
                if(sql.getMessage().contains("Could not set lock")) {
                    return "Database is locked by another process. Ensure no other Frigate instance is using the same database.";
                }
            }
            if(current instanceof JsonRpcException rpc && rpc.getMessage() != null) {
                if(rpc.getMessage().contains("-txindex")) {
                    return "Bitcoin Core requires txindex=1 in bitcoin.conf. Restart Bitcoin Core after adding it.";
                }
            }
            if(current instanceof ConfigurationException && configExceptionMessage == null) {
                configExceptionMessage = current.getMessage();
            }
            current = current.getCause();
        }
        return configExceptionMessage;
    }
}