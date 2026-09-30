package com.sparrowwallet.frigate;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterDescription;
import com.beust.jcommander.ParameterException;

import java.util.Arrays;

public final class CommandLine {
    private CommandLine() {
    }

    /**
     * Parses the arguments, printing the error and usage and exiting with status 1 if they are invalid, rather than starting with
     * defaults (such as on mainnet) in place of a mistyped or unknown option.
     */
    public static void parseOrExit(JCommander jCommander, String[] argv) {
        try {
            parse(jCommander, argv);
        } catch(ParameterException e) {
            System.err.println(e.getMessage());
            jCommander.usage();
            System.exit(1);
        }
    }

    /**
     * Parses the arguments, rejecting any not recognised: there are no positional arguments, so an unknown argument is a mistake.
     * @throws ParameterException if an argument is unknown, a value is invalid, or a flag is given a value
     */
    static void parse(JCommander jCommander, String[] argv) {
        jCommander.parse(argv);
        //Flags take no value, and the = separator would otherwise set the flag and pass the value on as an unknown argument
        for(ParameterDescription description : jCommander.getParameters()) {
            if(description.getParameterized().getType() == boolean.class) {
                for(String name : description.getParameter().names()) {
                    if(Arrays.stream(argv).anyMatch(arg -> arg.startsWith(name + "="))) {
                        throw new ParameterException("Option " + name + " does not take a value");
                    }
                }
            }
        }
        if(!jCommander.getUnknownOptions().isEmpty()) {
            throw new ParameterException("Unknown option: " + jCommander.getUnknownOptions().getFirst());
        }
    }
}
