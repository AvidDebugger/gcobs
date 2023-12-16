package net.szumigaj.gcobs.cli.telemetry;

import net.szumigaj.gcobs.cli.model.EnvironmentInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class EnvironmentSnapshot {

    public EnvironmentInfo capture() {
        EnvironmentInfo.EnvironmentInfoBuilder environmentInfoBuilder = EnvironmentInfo.builder()
                .javaVersion(System.getProperty("java.version"))
                .javaVendor(System.getProperty("java.vendor"))
                .javaRuntimeBuild(System.getProperty("java.runtime.version"))
                .javaVmName(System.getProperty("java.vm.name"))
                .javaVmVersion(System.getProperty("java.vm.version"))
                .javaHome(System.getProperty("java.home"))
                .osName(System.getProperty("os.name"))
                .osVersion(System.getProperty("os.version"))
                .availableProcessors(Runtime.getRuntime().availableProcessors());

        // Linux-specific: CPU model
        environmentInfoBuilder.cpuModel(readFirstMatch(
                Path.of("/proc/cpuinfo"),
                Pattern.compile("model name\\s*:\\s*(.+)")));

        // Kernel version
        environmentInfoBuilder.kernelVersion(runCommand("uname", "-r"));

        // Physical memory from /proc/meminfo
        String memTotalKb = readFirstMatch(
                Path.of("/proc/meminfo"),
                Pattern.compile("MemTotal:\\s*(\\d+)"));
        if (memTotalKb != null) {
            environmentInfoBuilder.physicalMemoryMb((int) (Long.parseLong(memTotalKb) / 1024));
        }

        // CPU governor
        environmentInfoBuilder.cpuGovernor(readFile(
                Path.of("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")));

        // cgroup v2 CPU quota
        environmentInfoBuilder.cgroupCpuQuota(readFile(Path.of("/sys/fs/cgroup/cpu.max")));

        // cgroup v2 memory limit
        String memLimit = readFile(Path.of("/sys/fs/cgroup/memory.max"));
        if (memLimit != null && !"max".equals(memLimit.trim())) {
            try {
                environmentInfoBuilder.cgroupMemoryLimitMb((int) (Long.parseLong(memLimit.trim()) / (1024 * 1024)));
            } catch (NumberFormatException ignored) {
                // non-numeric value (e.g. "max"), skip
            }
        }

        return environmentInfoBuilder.build();
    }

    private String readFirstMatch(Path file, Pattern pattern) {
        if (!Files.isReadable(file)) {
            return null;
        }
        try (var lines = Files.lines(file)) {
            return lines
                    .map(pattern::matcher)
                    .filter(Matcher::find)
                    .map(m -> m.group(1).trim())
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private String readFile(Path file) {
        if (!Files.isReadable(file)) {
            return null;
        }
        try {
            return Files.readString(file).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private String runCommand(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                process.waitFor();
                return line != null ? line.trim() : null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException e) {
            return null;
        }
    }
}
