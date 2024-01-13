package net.szumigaj.gcobs.cli.executor;

import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.szumigaj.gcobs.cli.artifact.*;
import net.szumigaj.gcobs.cli.compare.CompareResult;
import net.szumigaj.gcobs.cli.compare.ComparisonEngine;
import net.szumigaj.gcobs.cli.model.*;
import net.szumigaj.gcobs.cli.output.ConsoleTable;
import net.szumigaj.gcobs.cli.spec.EffectiveBenchmarkConfig;
import net.szumigaj.gcobs.cli.spec.SpecLoader;
import net.szumigaj.gcobs.cli.telemetry.EnvironmentSnapshot;
import net.szumigaj.gcobs.cli.telemetry.GcAnalyzer;
import net.szumigaj.gcobs.cli.telemetry.JfrExtractor;
import net.szumigaj.gcobs.cli.threshold.ThresholdResult;
import net.szumigaj.gcobs.cli.threshold.ThresholdValidator;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static net.szumigaj.gcobs.cli.threshold.ThresholdResult.ThresholdStatus.FAIL;

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
    private final GcAnalyzer gcAnalyzer;
    private final JfrExtractor jfrExtractor;
    private final ArtifactWriter artifactWriter;
    private final ComparisonEngine comparisonEngine;
    private final ConsoleTable consoleTable;

    public int execute(BenchmarkRunSpec spec, ExecutionOptions options) {
        try {
            return doExecute(spec, options);
        } catch (IOException e) {
            log.error("ERROR: {}", e.getMessage());
            return 2;
        }
    }

    private int doExecute(BenchmarkRunSpec spec, ExecutionOptions options) throws IOException {

        EnvironmentInfo envInfo = new EnvironmentSnapshot().capture();

        RunConfiguration runConfig = determineRunConfiguration(spec, options, envInfo);
        initializeRun(spec, options, runConfig);

        Instant startTime = Instant.now();
        List<BenchmarkResult> results = executeBenchmarks(spec, options, runConfig);

        if (options.dryRun()) {
            log.info("Dry run passed: {} ({} benchmarks)", spec.metadata().name(), spec.benchmarks().size());
            return 0;
        }

        String profileMode = resolveProfileMode(spec, options);
        List<CompareResult> compareResults = Collections.emptyList();
        if (spec.compare() != null && spec.compare().pairs() != null && !spec.compare().pairs().isEmpty()) {
            try {
                compareResults = comparisonEngine.compareIntraSpec(
                        spec.compare(), runConfig.runDir(), runConfig.runId, profileMode);
                for (CompareResult cr : compareResults) {
                    consoleTable.printDeltaTable(cr);
                }
            } catch (Exception e) {
                log.error("[gcobs] WARNING: Comparison failed: {}", e.getMessage());
            }
        }

        int exitCode = computeExitCode(results, profileMode);

        try {
            RunContext runContext = new RunContext(runConfig.runId, startTime, Instant.now(),
                    options.specPath(), spec, results, envInfo, runConfig.runDir, exitCode);
            RunSummaryContext summaryCtx = new RunSummaryContext(compareResults);
            artifactWriter.writeRunManifest(runContext, summaryCtx);
            artifactWriter.writeReport(runContext);
        } catch (IOException ae) {
            log.error("WARNING: Could not write run manifest: {}", ae.getMessage());
        }

        printSummary(runConfig.runId(), results, startTime, runConfig.runDir());

        return exitCode;
    }

    private RunConfiguration determineRunConfiguration(BenchmarkRunSpec spec, ExecutionOptions options, EnvironmentInfo envInfo) {
        String runId = options.runId() != null ? options.runId() : spec.metadata().name() + "-" + RUN_ID_FMT.format(Instant.now());

        Path runsDir;
        if (options.runsDir() != null) {
            runsDir = options.runsDir();
        } else if (spec.run() != null && spec.run().runsDir() != null) {
            runsDir = Path.of(spec.run().runsDir());
        } else {
            runsDir = Path.of("runs");
        }

        return new RunConfiguration(runId, runsDir, runsDir.resolve(runId), envInfo);
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

            BenchmarkResult result = executeSingleBenchmark(spec, runConfig, options, bench, effective);
            results.add(result);
        }

        return results;
    }

    private BenchmarkResult executeSingleBenchmark(BenchmarkRunSpec spec, RunConfiguration runConfiguration, ExecutionOptions options, BenchmarkEntry bench, EffectiveBenchmarkConfig effective) throws IOException {
        Path benchDir = runConfiguration.runDir.resolve("benchmarks").resolve(bench.id());
        Files.createDirectories(benchDir);

        ResolvedSource source = sourceResolver.resolve(bench.source(), options.projectRoot());
        printBenchmarkConfig(effective, options.noJfr());

        Instant benchStart = Instant.now();
        int jmhExitCode;
        try {
            jmhExitCode = jmhLauncher.launch(source, effective, benchDir, options.noJfr(), options.projectRoot());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Benchmark {} INTERRUPTED", bench.id());
            jmhExitCode = 1;
        }

        Duration benchDuration = Duration.between(benchStart, Instant.now());

        if (jmhExitCode != 0) {
            log.error("Benchmark {} FAILED (exit {})", bench.id(), jmhExitCode);

            summarizeFailedRun(runConfiguration.runId, bench, effective, benchStart, benchDuration, runConfiguration.envInfo, benchDir);

            return  new BenchmarkResult(bench.id(), jmhExitCode, benchDuration, null);

        } else {
            mergeGcLogs(benchDir);

            GcSummary gcSummary = analyzeGcLogs(runConfiguration.runId, bench, benchDir).orElse(null);

            JfrSummary jfrSummary = extractJfrData(runConfiguration.runId, bench, effective, options.noJfr(), benchDir).orElse(null);

            JmhScore jmhScore = JmhResultParser.parse(benchDir);

            String profileMode = resolveProfileMode(spec, options);
            String onMissingMetric = resolveOnMissingMetric(spec, options);
            ThresholdResult thresholdResult = ThresholdValidator.evaluate(
                    effective.thresholds(), gcSummary, jfrSummary,
                    null, jmhScore.score(),
                    profileMode, onMissingMetric);

            try {
                BenchmarkContext bctx = new BenchmarkContext(bench.id(), runConfiguration.runId, "success",
                        benchStart, Instant.now(), benchDuration.toMillis(),
                        effective, bench.source(), gcSummary, jfrSummary, runConfiguration.envInfo, benchDir);
                artifactWriter.writeBenchmarkSummary(bctx);
                jmhScore = JmhResultParser.parse(benchDir);
            } catch (IOException ae) {
                log.error("WARNING: Could not write benchmark summary for {}: {}",
                        bench.id(), ae.getMessage());
            }

            // Console GC summary table
            consoleTable.printGcTable(bench.id(), gcSummary, jmhScore);

            // Print threshold breach warnings
            if (thresholdResult != null && FAIL.equals(thresholdResult.status())) {
                log.error("THRESHOLD BREACH in {}:", bench.id());
                for (var b : thresholdResult.breaches()) {
                    log.error("   {}: {} > {} ({})",
                            b.field(), b.actual(), b.threshold(), b.message());
                }
            }

            log.info("Benchmark {}: completed in {}s (success)", bench.id(), benchDuration.getSeconds());

            return new BenchmarkResult(bench.id(), jmhExitCode, benchDuration, thresholdResult);
        }
    }

    private void summarizeFailedRun(String runId, BenchmarkEntry bench, EffectiveBenchmarkConfig effective, Instant benchStart, Duration benchDuration, EnvironmentInfo envInfo, Path benchDir) {
        try {
            BenchmarkContext bctx = new BenchmarkContext(bench.id(), runId, "failed",
                    benchStart, Instant.now(), benchDuration.toMillis(),
                    effective, bench.source(), null, null, envInfo, benchDir);
            artifactWriter.writeBenchmarkSummary(bctx);
        } catch (IOException ae) {
            log.error("WARNING: Could not write benchmark summary for %s: %s%n",
                    bench.id(), ae.getMessage());
        }
    }

    private Optional<JfrSummary> extractJfrData(String runId, BenchmarkEntry bench, EffectiveBenchmarkConfig effective, boolean noJfr, Path benchDir) {
        if (effective.jfrEnabled() && !noJfr) {
            try {
                return Optional.ofNullable(jfrExtractor.extract(benchDir, bench.id(), runId));
            } catch (IOException e) {
                log.error("WARNING: JFR extraction failed for %s: %s%n",
                        bench.id(), e.getMessage());
            }
        }
        return Optional.empty();
    }

    private Optional<GcSummary> analyzeGcLogs(String runId, BenchmarkEntry bench, Path benchDir) {
        try {
            return Optional.ofNullable(gcAnalyzer.analyze(benchDir, bench.id(), runId));
        } catch (IOException e) {
            log.error("WARNING: GC analysis failed for %s: %s%n",
                    bench.id(), e.getMessage());
        }
        return Optional.empty();
    }

    private void printSummary(String runId, List<BenchmarkResult> results, Instant startTime, Path runDir) {
        consoleTable.printRunSummary(runId, results,
                Duration.between(startTime, Instant.now()), runDir);
    }

    private int computeExitCode(List<BenchmarkResult> results, String profileMode) {
        boolean anyFailed = results.stream().anyMatch(BenchmarkResult::isFailure);
        boolean anyThresholdBreach = "invariant".equals(profileMode) && results.stream()
                .filter(BenchmarkResult::isSuccess)
                .anyMatch(r -> r.thresholdResult() != null
                        && FAIL.equals(r.thresholdResult().status()));
        return anyFailed || anyThresholdBreach ? 3 : 0;
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

    private String resolveProfileMode(BenchmarkRunSpec spec, ExecutionOptions options) {
        if (options.profile() != null) return options.profile();
        if (spec.run() != null && spec.run().profile() != null) return spec.run().profile();
        return "invariant";
    }

    private String resolveOnMissingMetric(BenchmarkRunSpec spec, ExecutionOptions options) {
        if (options.strictMetrics()) return "fail";
        if (spec.run() != null && spec.run().validation() != null
                && spec.run().validation().onMissingMetric() != null) {
            return spec.run().validation().onMissingMetric();
        }
        return "fail";
    }

    private record RunConfiguration(String runId, Path runsDir, Path runDir, EnvironmentInfo envInfo) {}
}
