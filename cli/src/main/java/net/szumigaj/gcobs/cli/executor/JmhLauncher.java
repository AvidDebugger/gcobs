package net.szumigaj.gcobs.cli.executor;

import jakarta.inject.Singleton;
import net.szumigaj.gcobs.cli.spec.EffectiveBenchmarkConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static net.szumigaj.gcobs.cli.executor.SourceType.INTERNAL;

/**
 * Builds the JMH command and executes it via ProcessBuilder,
 * using the Gradle runJmh task.
 */
@Singleton
public class JmhLauncher {

    /**
     * Launches JMH for a single benchmark.
     *
     * @param source   resolved source (contains Gradle task name)
     * @param config   effective benchmark config (merged from spec)
     * @param benchDir output directory for this benchmark's artifacts
     * @param noJfr    CLI override to disable JFR regardless of config
     * @return process exit code (0 = success)
     */
    public int launch(ResolvedSource source, EffectiveBenchmarkConfig config,
                      Path benchDir, boolean noJfr, Path projectRoot) throws IOException, InterruptedException {

        List<String> command = switch (source.type()) {
            case INTERNAL -> buildGradleCommand(source, config, benchDir, noJfr);
            case JAR, GRADLE -> buildJarCommand(source, config, benchDir, noJfr);
            default -> throw new IllegalArgumentException("Unsupported source type: " + source.type());
        };

        // Record the full command for reproducibility
        Files.writeString(benchDir.resolve("jmh.cmdline.txt"), String.join(" ", command));

        Path workDir = INTERNAL.equals(source.type()) ? projectRoot
                : (source.moduleDir() != null ? source.moduleDir() : projectRoot);

        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectOutput(benchDir.resolve("jmh.stdout.log").toFile())
                .redirectError(benchDir.resolve("jmh.stderr.log").toFile());

        Process process = pb.start();
        return process.waitFor();
    }

    List<String> buildGradleCommand(ResolvedSource source, EffectiveBenchmarkConfig config,
                                    Path benchDir, boolean noJfr) {

        Path absBenchDir = benchDir.toAbsolutePath();

        // --- Build jvmArgsAppend (joined with ||| delimiter) ---
        List<String> jvmArgs = buildJvmArgs(config, absBenchDir, noJfr);

        // 1. GC log flag
        jvmArgs.add("-Xlog:%s:file=%s/gc-%%p.log:time,uptimemillis,tags,level".formatted(
                config.gcLogTags(), absBenchDir));

        // 2. JFR flag (if enabled and not overridden by --no-jfr)
        if (config.jfrEnabled() && !noJfr) {
            jvmArgs.add("-XX:StartFlightRecording=filename=%s/profile-%%p.jfr,dumponexit=true,settings=%s".formatted(
                    absBenchDir, config.jfrSettings()));
        }

        if (config.heapDumpOnOutOfMemoryError()) {
            jvmArgs.add("-XX:+HeapDumpOnOutOfMemoryError");
            jvmArgs.add("-XX:HeapDumpPath=%s".formatted(absBenchDir));
        }

        // 3. User JVM args from spec
        if (config.jvmArgs() != null && !config.jvmArgs().isEmpty()) {
            jvmArgs.addAll(config.jvmArgs());
        }

        String jvmArgsAppend = String.join("|||", jvmArgs);

        // --- Build Gradle command ---
        List<String> cmd = new ArrayList<>();
        cmd.add("./gradlew");
        cmd.add(source.gradleTask());
        cmd.add("-PjvmArgsAppend=" + jvmArgsAppend);
        cmd.add("-PwarmupIterations=" + config.warmupIterations());
        cmd.add("-PmeasurementIterations=" + config.measurementIterations());
        cmd.add("-Pforks=" + config.forks());
        cmd.add("-Pthreads=" + config.threads());
        cmd.add("-PoutputFile=" + absBenchDir.resolve("jmh-results.json"));

        if (config.jmhIncludes() != null && !config.jmhIncludes().isEmpty()) {
            cmd.add("-Pincludes=" + config.jmhIncludes());
        }

        if (config.params() != null && !config.params().isEmpty()) {
            String paramsStr = config.params().entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(","));
            cmd.add("-Pparams=" + paramsStr);
        }

        return cmd;
    }

    /** Builds a direct java -cp command for type: jar and type: gradle. */
    List<String> buildJarCommand(ResolvedSource source, EffectiveBenchmarkConfig config,
                                 Path benchDir, boolean noJfr) {

        Path absBenchDir = benchDir.toAbsolutePath();
        Path jarPath = source.jarPath().toAbsolutePath();

        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-cp");
        cmd.add(jarPath.toString());

        // JMH main class
        cmd.add("org.openjdk.jmh.Main");

        // JMH flags
        cmd.add("-f");
        cmd.add(String.valueOf(config.forks()));
        cmd.add("-wi");
        cmd.add(String.valueOf(config.warmupIterations()));
        cmd.add("-i");
        cmd.add(String.valueOf(config.measurementIterations()));
        cmd.add("-t");
        cmd.add(String.valueOf(config.threads()));

        // Output format
        cmd.add("-rf");
        cmd.add("json");
        cmd.add("-rff");
        cmd.add(absBenchDir.resolve("jmh-results.json").toString());

        // JVM args: GC/JFR flags + user args passed via -jvmArgsAppend
        List<String> jvmArgs = buildJvmArgs(config, absBenchDir, noJfr);
        if (!jvmArgs.isEmpty()) {
            cmd.add("-jvmArgsAppend");
            cmd.add(String.join(" ", jvmArgs));
        }

        // JMH parameters
        if (config.params() != null && !config.params().isEmpty()) {
            for (var entry : config.params().entrySet()) {
                cmd.add("-p");
                cmd.add(entry.getKey() + "=" + entry.getValue());
            }
        }

        // Include regex (must be last positional argument)
        if (config.jmhIncludes() != null && !config.jmhIncludes().isEmpty()) {
            cmd.add(config.jmhIncludes());
        }

        return cmd;
    }

    /** Builds the common JVM args list (GC log, JFR, user args). */
    private List<String> buildJvmArgs(EffectiveBenchmarkConfig config,
                                      Path absBenchDir, boolean noJfr) {
        List<String> jvmArgs = new ArrayList<>();

        // 1. GC log flag
        jvmArgs.add(String.format(
                "-Xlog:%s:file=%s/gc-%%p.log:time,uptimemillis,tags,level",
                config.gcLogTags(), absBenchDir));

        // 2. JFR flag (if enabled and not overridden by --no-jfr)
        if (config.jfrEnabled() && !noJfr) {
            jvmArgs.add(String.format(
                    "-XX:StartFlightRecording=filename=%s/profile-%%p.jfr,dumponexit=true,settings=%s",
                    absBenchDir, config.jfrSettings()));
        }

        // 3. User JVM args from spec
        if (config.jvmArgs() != null && !config.jvmArgs().isEmpty()) {
            jvmArgs.addAll(config.jvmArgs());
        }

        return jvmArgs;
    }
}
