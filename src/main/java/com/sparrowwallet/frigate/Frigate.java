package com.sparrowwallet.frigate;

import com.beust.jcommander.JCommander;
import com.google.common.eventbus.EventBus;
import com.sparrowwallet.drongo.Drongo;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.frigate.electrum.ElectrumServerRunnable;
import com.sparrowwallet.frigate.bitcoind.BitcoindClient;
import com.sparrowwallet.frigate.index.Index;
import com.sparrowwallet.frigate.index.IndexQuerier;
import com.sparrowwallet.frigate.io.Config;
import com.sparrowwallet.frigate.io.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Locale;

public class Frigate {
    public static final String SERVER_NAME = "Frigate";
    public static final String SERVER_VERSION = "1.0.0";
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

    public void start() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));

        Integer startHeight = Config.get().getIndexStartHeight();
        if(startHeight == null) {
            startHeight = Network.get() == Network.MAINNET ? MAINNET_TAPROOT_ACTIVATION_HEIGHT : TESTNET_TAPROOT_ACTIVATION_HEIGHT;
            Config.get().setIndexStartHeight(startHeight);
        }

        blocksIndex = new Index(startHeight, false);
        mempoolIndex = new Index(0, true);

        Boolean startIndexing = Config.get().isStartIndexing();
        if(startIndexing == null) {
            startIndexing = true;
            Config.get().setStartIndexing(startIndexing);
        }

        if(startIndexing) {
            bitcoindClient = new BitcoindClient(blocksIndex, mempoolIndex);
            bitcoindClient.initialize();
        }

        electrumServer = new ElectrumServerRunnable(bitcoindClient, new IndexQuerier(blocksIndex, mempoolIndex));
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

        Frigate frigate = new Frigate();
        frigate.start();
    }
}