package com.sparrowwallet.frigate;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;
import com.sparrowwallet.drongo.Network;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CommandLineTest {
    private static Args parseServer(String... argv) {
        Args args = new Args();
        CommandLine.parse(JCommander.newBuilder().addObject(args).acceptUnknownOptions(true).build(), argv);
        return args;
    }

    private static com.sparrowwallet.frigate.cli.Args parseCli(String... argv) {
        com.sparrowwallet.frigate.cli.Args args = new com.sparrowwallet.frigate.cli.Args();
        CommandLine.parse(JCommander.newBuilder().addObject(args).acceptUnknownOptions(true).build(), argv);
        return args;
    }

    private static void assertRejected(String message, Runnable parse) {
        ParameterException e = assertThrows(ParameterException.class, parse::run);
        assertTrue(e.getMessage().contains(message), e.getMessage());
    }

    @Test
    public void validArgumentsAreParsedInEitherForm() {
        assertEquals(Network.TESTNET4, parseServer("-n", "testnet4").network);
        assertEquals(Network.TESTNET4, parseServer("--network=testnet4").network);
        assertTrue(parseServer("--version").version);

        com.sparrowwallet.frigate.cli.Args cli = parseCli("--host=127.0.0.1:57001", "-b", "100000", "--labels=1", "-a", "2", "-q");
        assertEquals("127.0.0.1:57001", cli.host);
        assertEquals(100000L, cli.start);
        assertEquals(List.of(1, 2), cli.labels);
        assertTrue(cli.quiet);
    }

    @Test
    public void unknownArgumentsAreRejected() {
        assertRejected("Unknown option: --netwrok", () -> parseServer("--netwrok", "testnet4", "--version"));
        assertRejected("Unknown option: --netwrok=testnet4", () -> parseServer("--netwrok=testnet4"));
        //there are no positional arguments, so a forgotten -n is an error rather than a start on mainnet
        assertRejected("Unknown option: testnet4", () -> parseServer("testnet4"));
        assertRejected("Unknown option: --scan", () -> parseCli("--scan", "abc"));
    }

    @Test
    public void invalidValuesAreRejected() {
        assertThrows(ParameterException.class, () -> parseServer("-n", "foo"));
        assertThrows(ParameterException.class, () -> parseServer("--level=foo"));
        assertThrows(ParameterException.class, () -> parseCli("-b", "abc"));
        assertRejected("should be an integer", () -> parseCli("-a", "x"));
        assertRejected("should be positive", () -> parseCli("-a", "-1"));
    }

    @Test
    public void flagsGivenValuesAreRejected() {
        assertRejected("Option --version does not take a value", () -> parseServer("--version=false"));
        assertRejected("Option --quiet does not take a value", () -> parseCli("--quiet=false"));
        assertRejected("Option -f does not take a value", () -> parseCli("-f=true"));
    }
}
