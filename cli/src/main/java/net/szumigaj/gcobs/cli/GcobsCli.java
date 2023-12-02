package net.szumigaj.gcobs.cli;

import io.micronaut.configuration.picocli.PicocliRunner;
import net.szumigaj.gcobs.cli.command.RunExecuteCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "gcobs",
         mixinStandardHelpOptions = true,
         version = "gcobs 0.0.1",
         description = "Java GC Observatory",
         subcommands = {
            RunExecuteCommand.class,
         })
public class GcobsCli implements Runnable {

    public static void main(String[] args) {
        PicocliRunner.run(GcobsCli.class, args);
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
