package com.sparrowwallet.frigate.cli;

import com.beust.jcommander.Parameter;
import com.sparrowwallet.drongo.Network;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.List;

public class Args {
    @Parameter(names = { "--dir", "-d" }, description = "Path to Frigate home folder")
    public String dir;

    @Parameter(names = { "--network", "-n" }, description = "Network to use")
    public Network network;

    @Parameter(names = { "--level", "-l" }, description = "Set log level")
    public Level level;

    @Parameter(names = { "--version", "-v" }, description = "Show version", arity = 0)
    public boolean version;

    @Parameter(names = { "--help" }, description = "Show usage", help = true)
    public boolean help;

    @Parameter(names = { "--host", "-h" }, description = "Electrum index server host")
    public String host;

    @Parameter(names = { "--scanPrivateKey", "-s" }, description = "Scan private key")
    public String scanPrivateKey;

    @Parameter(names = { "--spendPublicKey", "-S" }, description = "Spend public key")
    public String spendPublicKey;

    @Parameter(names = { "--startHeight", "-b" }, description = "Scan start height")
    public Integer startHeight;

    @Parameter(names = { "--endHeight", "-e" }, description = "Scan end height")
    public Integer endHeight;

    public List<String> toParams() {
        List<String> params = new ArrayList<>();

        if(dir != null) {
            params.add("-d");
            params.add(dir);
        }
        if(network != null) {
            params.add("-n");
            params.add(network.toString());
        }
        if(level != null) {
            params.add("-l");
            params.add(level.toString());
        }

        return params;
    }
}
