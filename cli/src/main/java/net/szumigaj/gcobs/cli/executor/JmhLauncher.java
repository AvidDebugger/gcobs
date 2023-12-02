package net.szumigaj.gcobs.cli.executor;

import jakarta.inject.Singleton;
import net.szumigaj.gcobs.cli.spec.EffectiveBenchmarkConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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

        List<String> command = buildCommand(source, config, benchDir, noJfr);

        // Record the full command for reproducibility
        Files.writeString(benchDir.resolve("jmh.cmdline.txt"), String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(projectRoot.toFile())
                .redirectOutput(benchDir.resolve("jmh.stdout.log").toFile())
                .redirectError(benchDir.resolve("jmh.stderr.log").toFile());

        Process process = pb.start();
        return process.waitFor();
    }

    List<String> buildCommand(ResolvedSource source, EffectiveBenchmarkConfig config,
                              Path benchDir, boolean noJfr) {

        Path absBenchDir = benchDir.toAbsolutePath();

        // --- Build jvmArgsAppend (joined with ||| delimiter) ---
        List<String> jvmArgs = new ArrayList<>();

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
}
