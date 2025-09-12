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

    @Parameter(names = { "--start", "-b" }, description = "Scan start block height or timestamp")
    public Long start;

    @Parameter(names = { "--follow", "-f" }, description = "Keep client open after initial scan to receive additional transaction")
    public boolean follow;

    @Parameter(names = { "--quiet", "-q" }, description = "Disable printing of the progress bar")
    public boolean quiet;

    @Parameter(names = { "--height" }, description = "Query tweaks for specific block height")
    public Integer blockHeight;
}
