package com.sparrowwallet.frigate.cli;

import com.beust.jcommander.JCommander;
import com.github.arteam.simplejsonrpc.client.JsonRpcClient;
import com.google.common.net.HostAndPort;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sparrowwallet.drongo.Drongo;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.frigate.Frigate;
import com.sparrowwallet.frigate.index.TxEntry;
import com.sparrowwallet.frigate.io.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Collection;
import java.util.Locale;
import java.util.Scanner;

import static com.sparrowwallet.frigate.Frigate.*;

public class FrigateCli {
    private static final String APP_NAME = "frigate-cli";

    private final HostAndPort server;
    private String scanPrivateKey;
    private String spendPublicKey;
    private Integer startHeight;
    private Integer endHeight;

    public FrigateCli(HostAndPort server, String scanPrivateKey, String spendPublicKey, Integer startHeight, Integer endHeight) {
        this.server = server;
        this.scanPrivateKey = scanPrivateKey;
        this.spendPublicKey = spendPublicKey;
        this.startHeight = startHeight;
        this.endHeight = endHeight;
    }

    public void promptForMissingValues() {
        boolean requestHeights = (scanPrivateKey == null && spendPublicKey == null);
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

        if(requestHeights) {
            if(startHeight == null) {
                System.out.print("Enter start height (optional, press Enter to skip): ");
                String input = scanner.nextLine().trim();
                if(!input.isEmpty()) {
                    try {
                        startHeight = Integer.parseInt(input);
                    } catch(NumberFormatException e) {
                        System.out.println("Invalid number format for start height. Skipping...");
                    }
                }
            }

            if(endHeight == null) {
                System.out.print("Enter end height (optional, press Enter to skip): ");
                String input = scanner.nextLine().trim();
                if(!input.isEmpty()) {
                    try {
                        endHeight = Integer.parseInt(input);
                    } catch(NumberFormatException e) {
                        System.out.println("Invalid number format for end height. Skipping...");
                    }
                }
            }
        }
    }

    public void scan() {
        JsonRpcClient jsonRpcClient = new JsonRpcClient(new ElectrumTransport(server));
        ElectrumClientService electrumClientService = jsonRpcClient.onDemand(ElectrumClientService.class);
        long start = System.currentTimeMillis();
        Collection<TxEntry> history = electrumClientService.getSilentPaymentsHistory(scanPrivateKey, spendPublicKey, startHeight, endHeight);
        long elapsed = System.currentTimeMillis() - start;
        getLogger().debug("\nScan took " + (elapsed < 1000 ? elapsed + "ms" : elapsed/1000 + "s"));

        GsonBuilder gsonBuilder = new GsonBuilder();
        Gson gson = gsonBuilder.setPrettyPrinting().disableHtmlEscaping().create();
        gson.toJson(history, System.out);
        System.out.println();
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

        HostAndPort server = HostAndPort.fromString(args.host == null ? "127.0.0.1" : args.host);

        FrigateCli frigateCli = new FrigateCli(server, args.scanPrivateKey, args.spendPublicKey, args.startHeight, args.endHeight);
        frigateCli.promptForMissingValues();
        frigateCli.scan();
    }
}
