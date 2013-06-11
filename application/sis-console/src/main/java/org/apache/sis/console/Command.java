/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sis.console;

import java.util.Locale;
import java.io.Console;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.IOException;
import java.sql.SQLException;
import org.opengis.referencing.operation.TransformException;
import org.apache.sis.storage.DataStoreException;
import org.apache.sis.util.Exceptions;
import org.apache.sis.util.resources.Errors;


/**
 * Command line interface for Apache SIS.
 * The main method can be invoked from the command-line as below
 * (the filename needs to be completed with the actual version number):
 *
 * {@preformat java
 *     java -jar target/binaries/sis-console.jar
 * }
 *
 * "{@code target/binaries}" is the default location where SIS JAR files are grouped together
 * with their dependencies after a Maven build. This directory can be replaced by any path to
 * a directory providing the required dependencies.
 *
 * @author  Martin Desruisseaux (Geomatys)
 * @since   0.3
 * @version 0.3
 * @module
 */
public final class Command {
    /**
     * The code given to {@link System#exit(int)} when the program failed because of a unknown sub-command.
     */
    public static final int INVALID_COMMAND_EXIT_CODE = 1;

    /**
     * The code given to {@link System#exit(int)} when the program failed because of an illegal user argument.
     */
    public static final int INVALID_OPTION_EXIT_CODE = 2;

    /**
     * The code given to {@link System#exit(int)} when a file given in argument uses an unknown file format.
     */
    public static final int UNKNOWN_STORAGE_EXIT_CODE = 10;

    /**
     * The code given to {@link System#exit(int)} when the program failed because of an {@link java.io.IOException}.
     */
    public static final int IO_EXCEPTION_EXIT_CODE = 100;

    /**
     * The code given to {@link System#exit(int)} when the program failed because of an {@link java.sql.SQLException}.
     */
    public static final int SQL_EXCEPTION_EXIT_CODE = 101;

    /**
     * The code given to {@link System#exit(int)} when the program failed for a reason
     * other than the ones enumerated in the above constants.
     */
    public static final int FAILURE_EXIT_CODE = 199;

    /**
     * The sub-command name.
     */
    private final String commandName;

    /**
     * The sub-command to execute.
     */
    private final SubCommand command;

    /**
     * Creates a new command for the given arguments. The first value in the given array which is
     * not an option is taken as the command name. All other values are options or filenames.
     *
     * @param  args The command-line arguments.
     * @throws InvalidCommandException If an invalid command has been given.
     * @throws InvalidOptionException If the given arguments contain an invalid option.
     */
    protected Command(final String[] args) throws InvalidCommandException, InvalidOptionException {
        int commandIndex = -1;
        String commandName = null;
        for (int i=0; i<args.length; i++) {
            final String arg = args[i];
            if (arg.startsWith(Option.PREFIX)) {
                final String name = arg.substring(Option.PREFIX.length());
                final Option option;
                try {
                    option = Option.valueOf(name.toUpperCase(Locale.US));
                } catch (IllegalArgumentException e) {
                    throw new InvalidOptionException(Errors.format(Errors.Keys.UnknownOption_1, name), e, name);
                }
                if (option.hasValue) {
                    i++; // Skip the next argument.
                }
            } else {
                // Takes the first non-argument option as the command name.
                commandName = arg;
                commandIndex = i;
                break;
            }
        }
        if (commandName == null) {
            command = new HelpSC(-1, args);
        } else {
            commandName = commandName.toLowerCase(Locale.US);
            switch (commandName) {
                case "help":     command = new HelpSC    (commandIndex, args); break;
                case "about":    command = new AboutSC   (commandIndex, args); break;
                case "metadata": command = new MetadataSC(commandIndex, args); break;
                default: throw new InvalidCommandException(Errors.format(
                            Errors.Keys.UnknownCommand_1, commandName), commandName);
            }
        }
        this.commandName = commandName;
    }

    /**
     * Runs the command. If an exception occurs, then the exception message is sent to the error output
     * stream before to be thrown. Callers can map the exception to a system exit code by the
     * {@link #exitCodeFor(Throwable)} method.
     *
     * @throws Exception If an error occurred during the command execution. This is typically, but not limited, to
     *         {@link IOException}, {@link SQLException}, {@link DataStoreException} or {@link TransformException}.
     */
    public void run() throws Exception {
        if (command.options.containsKey(Option.HELP)) {
            command.help(commandName);
        } else try {
            command.run();
        } catch (Exception e) {
            command.out.flush();
            command.err.println(Exceptions.formatChainedMessages(command.locale, null, e));
            throw e;
        }
    }

    /**
     * Returns the exit code for the given exception, or 0 if unknown. This method iterates through the
     * {@linkplain Throwable#getCause() causes} until an exception matching a {@code *_EXIT_CODE}
     * constant is found.
     *
     * @param  cause The exception for which to get the exit code.
     * @return The exit code as one of the {@code *_EXIT_CODE} constant, or {@link #FAILURE_EXIT_CODE} if unknown.
     */
    public static int exitCodeFor(Throwable cause) {
        while (cause != null) {
            if (cause instanceof IOException) {
                return IO_EXCEPTION_EXIT_CODE;
            }
            if (cause instanceof SQLException) {
                return SQL_EXCEPTION_EXIT_CODE;
            }
            cause = cause.getCause();
        }
        return FAILURE_EXIT_CODE;
    }

    /**
     * Prints the message of the given exception. This method is invoked only when the error occurred before
     * the {@link SubCommand} has been built, otherwise the {@link SubCommand#err} printer shall be used.
     */
    private static void error(final Exception e) {
        final Console console = System.console();
        if (console != null) {
            final PrintWriter err = console.writer();
            err.println(e.getLocalizedMessage());
            err.flush();
        } else {
            final PrintStream err = System.err;
            err.println(e.getLocalizedMessage());
            err.flush();
        }
    }

    /**
     * Prints the information to the standard output stream.
     *
     * @param args Command-line options.
     */
    public static void main(final String[] args) {
        final Command c;
        try {
            c = new Command(args);
        } catch (InvalidCommandException e) {
            error(e);
            System.exit(INVALID_COMMAND_EXIT_CODE);
            return;
        } catch (InvalidOptionException e) {
            error(e);
            System.exit(INVALID_OPTION_EXIT_CODE);
            return;
        }
        try {
            c.run();
        } catch (Exception e) {
            System.exit(exitCodeFor(e));
        }
    }
}
