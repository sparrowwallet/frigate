package com.sparrowwallet.frigate.cli;

import com.beust.jcommander.JCommander;
import com.github.arteam.simplejsonrpc.client.JsonRpcClient;
import com.google.common.eventbus.EventBus;
import com.google.common.net.HostAndPort;
import com.sparrowwallet.drongo.Drongo;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.frigate.Frigate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Locale;
import java.util.Scanner;

import static com.sparrowwallet.frigate.Frigate.*;

public class FrigateCli implements Thread.UncaughtExceptionHandler {
    private static final String APP_NAME = "frigate-cli";

    private final HostAndPort server;
    private String scanPrivateKey;
    private String spendPublicKey;
    private Long start;

    private static ElectrumTransport transport;
    private Thread reader;

    private static final EventBus EVENT_BUS = new EventBus();

    public FrigateCli(HostAndPort server, String scanPrivateKey, String spendPublicKey, Long start) {
        this.server = server;
        this.scanPrivateKey = scanPrivateKey;
        this.spendPublicKey = spendPublicKey;
        this.start = start;
    }

    public void promptForMissingValues() {
        boolean requestStart = (scanPrivateKey == null && spendPublicKey == null);
        Scanner scanner = new Scanner(System.in);

        while(scanPrivateKey == null || scanPrivateKey.trim().length() != 64) {
            System.out.print("Enter scan private key: ");
            scanPrivateKey = scanner.nextLine().trim();
            if(scanPrivateKey.length() != 64) {
                System.out.println("Invalid scan private key.");
            }
        }

        while(spendPublicKey == null || spendPublicKey.trim().length() != 66) {
            System.out.print("Enter spend public key: ");
            spendPublicKey = scanner.nextLine().trim();
            if(spendPublicKey.length() != 66) {
                System.out.println("Invalid spend public key.");
            }
        }

        if(requestStart && start == null) {
            System.out.print("Enter start block height or timestamp (optional, press Enter to skip): ");
            String input = scanner.nextLine().trim();
            if(!input.isEmpty()) {
                try {
                    start = Long.parseLong(input);
                } catch(NumberFormatException e) {
                    System.out.println("Invalid number format for start height. Skipping...");
                }
            }
        }
    }

    public void connect() {
        transport = new ElectrumTransport(server);
        reader = new Thread(new ReadRunnable(), "ElectrumServerReadThread");
        reader.setDaemon(true);
        reader.setUncaughtExceptionHandler(FrigateCli.this);
        reader.start();
    }

    public void scan(boolean follow, boolean quiet) {
        JsonRpcClient jsonRpcClient = new JsonRpcClient(getTransport());
        ElectrumClientService electrumClientService = jsonRpcClient.onDemand(ElectrumClientService.class);
        String address = electrumClientService.subscribeSilentPayments(scanPrivateKey, spendPublicKey, start);

        try {
            ScanProgress scanProgress = new ScanProgress(address, !follow, !quiet);
            getEventBus().register(scanProgress);
            if(!quiet) {
                System.out.println("Scanning address " + address + "...");
            }
            scanProgress.waitForCompletion();
            getEventBus().unregister(scanProgress);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void close() {
        try {
            transport.close();
        } catch(IOException e) {
            getLogger().error("Error closing transport", e);
        }

        if(reader != null && reader.isAlive()) {
            reader.interrupt();
        }
    }

    private static Logger getLogger() {
        return LoggerFactory.getLogger(Frigate.class);
    }

    public static void main(String[] argv) {
        Args args = new Args();
        JCommander jCommander = JCommander.newBuilder().addObject(args).programName(APP_NAME.toLowerCase(Locale.ROOT)).acceptUnknownOptions(true).build();
        jCommander.parse(argv);
        if(args.help) {
            jCommander.usage();
            System.exit(0);
        }

        if(args.version) {
            System.out.println(APP_NAME + " " + SERVER_VERSION);
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

        HostAndPort server = HostAndPort.fromString(args.host == null ? "127.0.0.1" : args.host);

        FrigateCli frigateCli = new FrigateCli(server, args.scanPrivateKey, args.spendPublicKey, args.start);
        frigateCli.promptForMissingValues();
        frigateCli.connect();
        frigateCli.scan(args.follow, args.quiet);
        frigateCli.close();
    }

    public static ElectrumTransport getTransport() {
        return transport;
    }

    public static EventBus getEventBus() {
        return EVENT_BUS;
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        getLogger().error("Uncaught exception in thread " + t.getName(), e);
    }

    public static class ReadRunnable implements Runnable {
        @Override
        public void run() {
            try {
                ElectrumTransport tcpTransport = getTransport();
                tcpTransport.readInputLoop();

                if(tcpTransport.getLastException() != null) {
                    getLogger().error("Connection to Electrum server lost", tcpTransport.getLastException());
                    System.exit(1);
                }
            } catch(Exception e) {
                getLogger().debug("Read thread terminated", e);
            }
        }
    }
}
