package com.sparrowwallet.frigate;

import com.beust.jcommander.JCommander;
import com.google.common.eventbus.EventBus;
import com.sparrowwallet.drongo.Drongo;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.frigate.electrum.ElectrumServerRunnable;
import com.sparrowwallet.frigate.bitcoind.BitcoindClient;
import com.sparrowwallet.frigate.io.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Locale;

public class Frigate {
    public static final String SERVER_NAME = "Frigate";
    public static final String SERVER_VERSION = "0.0.1";
    public static final String APP_HOME_PROPERTY = "frigate.home";
    public static final String NETWORK_ENV_PROPERTY = "FRIGATE_NETWORK";

    private static final EventBus EVENT_BUS = new EventBus();

    private BitcoindClient bitcoindClient;
    private ElectrumServerRunnable electrumServer;

    private boolean running;

    public void start() {
        bitcoindClient = new BitcoindClient();
        bitcoindClient.initialize();

        electrumServer = new ElectrumServerRunnable(bitcoindClient);
        Thread electrumServerThread = new Thread(electrumServer, "Frigate Electrum Server");
        electrumServerThread.setDaemon(true);
        electrumServerThread.start();

        running = true;
    }

    public boolean isRunning() {
        return running;
    }

    public void stop() {
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
            getLogger().info("Using configured Sparrow home folder of " + args.dir);
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