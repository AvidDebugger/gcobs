package net.szumigaj.gcobs.cli.executor;

import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.szumigaj.gcobs.cli.model.BenchmarkEntry;
import net.szumigaj.gcobs.cli.model.BenchmarkRunSpec;
import net.szumigaj.gcobs.cli.spec.EffectiveBenchmarkConfig;
import net.szumigaj.gcobs.cli.spec.SpecLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates benchmark execution: iterates over benchmarks in a spec,
 * launches JMH, captures output, and creates the run directory structure.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor
public class BenchmarkExecutor {

    private static final DateTimeFormatter RUN_ID_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final SourceResolver sourceResolver;
    private final JmhLauncher jmhLauncher;

    public int execute(BenchmarkRunSpec spec, ExecutionOptions options) {
        try {
            return doExecute(spec, options);
        } catch (IOException e) {
            log.error("ERROR: {}", e.getMessage());
            return 2;
        }
    }

    private int doExecute(BenchmarkRunSpec spec, ExecutionOptions options) throws IOException {
        RunConfiguration runConfig = determineRunConfiguration(spec, options);
        initializeRun(spec, options, runConfig);

        Instant startTime = Instant.now();
        List<BenchmarkResult> results = executeBenchmarks(spec, options, runConfig);

        if (options.dryRun()) {
            log.info("Dry run passed: {} ({} benchmarks)", spec.metadata().name(), spec.benchmarks().size());
            return 0;
        }

        return computeExitCodeAndPrintSummary(runConfig.runId(), results, startTime, runConfig.runDir());
    }

    private RunConfiguration determineRunConfiguration(BenchmarkRunSpec spec, ExecutionOptions options) {
        String runId = options.runId() != null ? options.runId() : spec.metadata().name() + "-" + RUN_ID_FMT.format(Instant.now());

        Path runsDir;
        if (options.runsDir() != null) {
            runsDir = options.runsDir();
        } else if (spec.run() != null && spec.run().runsDir() != null) {
            runsDir = Path.of(spec.run().runsDir());
        } else {
            runsDir = Path.of("runs");
        }

        return new RunConfiguration(runId, runsDir, runsDir.resolve(runId));
    }

    private void initializeRun(BenchmarkRunSpec spec, ExecutionOptions options, RunConfiguration runConfig) throws IOException {
        if (!options.dryRun()) {
            Files.createDirectories(runConfig.runDir());
            Files.copy(options.specPath(), runConfig.runDir().resolve("run-spec.yaml"));
        }

        log.info("Loading spec: {}", options.specPath());
        log.info("Spec valid: {} ({} benchmarks{})", spec.metadata().name(), spec.benchmarks().size(),
                spec.run() != null && spec.run().profile() != null ? ", profile: " + spec.run().profile() : "");
        log.info("Run ID: {}", runConfig.runId());
        log.info("Output directory: {}/", runConfig.runDir());
    }

    private List<BenchmarkResult> executeBenchmarks(BenchmarkRunSpec spec, ExecutionOptions options, RunConfiguration runConfig) throws IOException {
        List<BenchmarkResult> results = new ArrayList<>();
        SpecLoader loader = new SpecLoader();

        for (int i = 0; i < spec.benchmarks().size(); i++) {
            BenchmarkEntry bench = spec.benchmarks().get(i);

            if (options.benchmarkFilter() != null && !options.benchmarkFilter().contains(bench.id())) {
                continue;
            }

            EffectiveBenchmarkConfig effective = loader.getEffective(spec, i);
            log.info("Executing benchmark {}/{}: {} ({}: {})", i + 1, spec.benchmarks().size(), bench.id(), bench.source().type(), sourceLabel(bench));

            if (options.dryRun()) {
                printDryRunConfig(effective, options.noJfr());
                continue;
            }

            BenchmarkResult result = executeSingleBenchmark(bench, effective, runConfig.runDir(), options.noJfr(), options.projectRoot());
            results.add(result);
        }

        return results;
    }

    private BenchmarkResult executeSingleBenchmark(BenchmarkEntry bench, EffectiveBenchmarkConfig effective, Path runDir, boolean noJfr, Path projectRoot) throws IOException {
        Path benchDir = runDir.resolve("benchmarks").resolve(bench.id());
        Files.createDirectories(benchDir);

        ResolvedSource source = sourceResolver.resolve(bench.source(), projectRoot);
        printBenchmarkConfig(effective, noJfr);

        Instant benchStart = Instant.now();
        int jmhExitCode;
        try {
            jmhExitCode = jmhLauncher.launch(source, effective, benchDir, noJfr, projectRoot);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Benchmark {} INTERRUPTED", bench.id());
            jmhExitCode = 1;
        }

        Duration benchDuration = Duration.between(benchStart, Instant.now());
        BenchmarkResult result = new BenchmarkResult(bench.id(), jmhExitCode, benchDuration);

        if (jmhExitCode != 0) {
            log.error("Benchmark {} FAILED (exit {})", bench.id(), jmhExitCode);
        } else {
            mergeGcLogs(benchDir);
            log.info("Benchmark {}: completed in {}s (success)", bench.id(), benchDuration.getSeconds());
        }

        return result;
    }

    private int computeExitCodeAndPrintSummary(String runId, List<BenchmarkResult> results, Instant startTime, Path runDir) {
        printRunSummary(runId, results, startTime, runDir);

        results.forEach(r -> log.info("Benchmark {}: exit {}", r.benchmarkId(), r.exitCode()));

        boolean anyFailed = results.stream().anyMatch(BenchmarkResult::isFailure);
        return anyFailed ? 3 : 0;
    }

    private void printBenchmarkConfig(EffectiveBenchmarkConfig config, boolean noJfr) {
        if (config.jvmArgs() != null && !config.jvmArgs().isEmpty()) {
            log.info("JVM args: {}", String.join(" ", config.jvmArgs()));
        }
        log.info("JMH: forks={} warmup={} measurement={} threads={}", config.forks(), config.warmupIterations(), config.measurementIterations(), config.threads());
        if (config.jmhIncludes() != null && !config.jmhIncludes().isEmpty()) {
            log.info("Includes: {}", config.jmhIncludes());
        }
        boolean jfrActive = config.jfrEnabled() && !noJfr;
        log.info("JFR: {}{}", jfrActive ? "enabled" : "disabled", jfrActive ? " (" + config.jfrSettings() + ")" : "");
    }

    private void printDryRunConfig(EffectiveBenchmarkConfig config, boolean noJfr) {
        printBenchmarkConfig(config, noJfr);
        if (config.params() != null && !config.params().isEmpty()) {
            log.info("  Params: {}", config.params());
        }
    }

    private void printRunSummary(String runId, List<BenchmarkResult> results, Instant startTime, Path runDir) {
        long succeeded = results.stream().filter(BenchmarkResult::isSuccess).count();
        long totalSecs = Duration.between(startTime, Instant.now()).getSeconds();

        log.info("Run {} complete: {}/{} benchmarks succeeded in {}s", runId, succeeded, results.size(), totalSecs);
        log.info("Artifacts: {}/", runDir);

        if (succeeded < results.size()) {
            log.info("Failed benchmarks:");
            results.stream().filter(r -> !r.isSuccess()).forEach(r -> log.info("  - {} (exit {})", r.benchmarkId(), r.exitCode()));
        }
    }

    private void mergeGcLogs(Path benchDir) throws IOException {
        List<Path> gcLogs;
        try (var paths = Files.list(benchDir)) {
            gcLogs = paths.filter(p -> p.getFileName().toString().matches("gc-\\d+\\.log"))
                    .sorted()
                    .toList();
        }

        if (gcLogs.isEmpty()) {
            return;
        }

        try (var writer = Files.newBufferedWriter(benchDir.resolve("gc.log"))) {
            for (Path gcLog : gcLogs) {
                writeLogFileWithHeader(writer, gcLog);
            }
        }
    }

    private void writeLogFileWithHeader(BufferedWriter writer, Path logFile) throws IOException {
        writer.write("# === Fork: " + logFile.getFileName() + " ===\n");
        for (String line : Files.readAllLines(logFile)) {
            writer.write(line + "\n");
        }
        writer.write("\n");
    }

    private String sourceLabel(BenchmarkEntry bench) {
        if (bench.source().module() != null) {
            return bench.source().module();
        }
        if (bench.source().projectDir() != null) {
            return bench.source().projectDir();
        }
        return bench.source().type();
    }

    private record RunConfiguration(String runId, Path runsDir, Path runDir) {}
}
