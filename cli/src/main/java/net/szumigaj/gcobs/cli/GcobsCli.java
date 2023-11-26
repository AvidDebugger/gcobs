package net.szumigaj.gcobs.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import java.util.concurrent.Callable;

@Command(name = "gcobs",
         mixinStandardHelpOptions = true,
         version = "gcobs 0.0.1",
         description = "Java GC Observatory",
         subcommands = {
         })
public class GcobsCli implements Callable<Integer> {

    public static void main(String[] args) {
        System.exit(new CommandLine(new GcobsCli()).execute(args));
    }

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }
}
