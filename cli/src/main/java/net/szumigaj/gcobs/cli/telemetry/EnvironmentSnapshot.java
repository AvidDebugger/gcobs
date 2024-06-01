package net.szumigaj.gcobs.cli.telemetry;

import lombok.extern.slf4j.Slf4j;
import net.szumigaj.gcobs.cli.model.env.EnvironmentInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class EnvironmentSnapshot {

    public EnvironmentInfo capture() {
        String javaHome = System.getProperty("java.home");
        String javaHomePath = System.getenv("JAVA_HOME");
        String javaVersion = System.getProperty("java.version");
        Integer javaMajorVersion = parseJdkMajorVersion(javaVersion);
        String javaVendor = System.getProperty("java.vendor");
        String javaVmName = System.getProperty("java.vm.name");

        EnvironmentInfo.EnvironmentInfoBuilder environmentInfoBuilder = EnvironmentInfo.builder()
                .javaVersion(javaVersion)
                .javaVendor(javaVendor)
                .javaRuntimeBuild(System.getProperty("java.runtime.version"))
                .javaVmName(javaVmName)
                .javaVmVersion(System.getProperty("java.vm.version"))
                .javaHome(javaHome)
                .osName(System.getProperty("os.name"))
                .osVersion(System.getProperty("os.version"))
                .availableProcessors(Runtime.getRuntime().availableProcessors())
                .javaHomePath(javaHomePath == null ? javaHome : javaHomePath)
                .jdkMajorVersion(javaMajorVersion)
                .jvmDistribution(javaVendor);

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

        // Warn if JDK < 11 (JFR unavailable)
        if (javaMajorVersion != null && javaMajorVersion < 11) {
            log.warn("WARNING: JDK {} detected. JFR is unavailable before JDK 11.", javaMajorVersion);
        }

        return environmentInfoBuilder.build();
    }

    static Integer parseJdkMajorVersion(String javaVersion) {
        if (javaVersion == null) {
            return null;
        }
        try {
            // JDK 9+: "17.0.1", "17", "11.0.2"
            // JDK 8: "1.8.0_301"
            if (javaVersion.startsWith("1.")) {
                return Integer.parseInt(javaVersion.substring(2, 3));
            }
            int dotIndex = javaVersion.indexOf('.');
            String major = dotIndex > 0 ? javaVersion.substring(0, dotIndex) : javaVersion;
            return Integer.parseInt(major);
        } catch (NumberFormatException e) {
            return null;
        }
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
