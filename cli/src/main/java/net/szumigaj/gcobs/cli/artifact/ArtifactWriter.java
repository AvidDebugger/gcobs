package net.szumigaj.gcobs.cli.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import net.szumigaj.gcobs.cli.executor.BenchmarkResult;
import net.szumigaj.gcobs.cli.model.result.GcSummary;
import net.szumigaj.gcobs.cli.model.result.JfrSummary;
import net.szumigaj.gcobs.cli.spec.EffectiveBenchmarkConfig;
import net.szumigaj.gcobs.cli.telemetry.JsonWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

import static net.szumigaj.gcobs.cli.threshold.ThresholdResult.ThresholdStatus.FAIL;
import static net.szumigaj.gcobs.cli.threshold.ThresholdResult.ThresholdStatus.PASS;

@Slf4j
@Singleton
public final class ArtifactWriter {

    private static final DateTimeFormatter ISO_FMT =
            DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

    // File names
    private static final String BENCHMARK_SUMMARY_JSON = "benchmark-summary.json";
    private static final String GC_SUMMARY_JSON = "gc-summary.json";
    private static final String GC_SUMMARY_WARMUP_JSON = "gc-summary-warmup.json";
    private static final String JFR_SUMMARY_JSON = "jfr-summary.json";
    private static final String JMH_RESULTS_JSON = "jmh-results.json";
    private static final String JMH_RESULTS_CSV = "jmh-results.csv";
    private static final String JMH_CMDLINE_TXT = "jmh.cmdline.txt";
    private static final String JMH_STDOUT_LOG = "jmh.stdout.log";
    private static final String JMH_STDERR_LOG = "jmh.stderr.log";
    private static final String METRICS_TIMESERIES_JSONL = "metrics-timeseries.jsonl";
    private static final String RUN_SPEC_YAML = "run-spec.yaml";
    private static final String RUN_JSON = "run.json";
    private static final String REPORT_MD = "report.md";
    private static final String GC_LOG = "gc.log";

    // File patterns
    private static final String JFR_FILE_PATTERN = "profile-.*\\.jfr";
    private static final String GC_LOG_PATTERN = "gc-\\d+\\.log";

    // Format strings
    private static final String GC_LOG_FLAG_FORMAT = "-Xlog:%s:file=%s/gc-%%p.log:time,uptimemillis,tags,level";
    private static final String JFR_FLAG_FORMAT = "-XX:StartFlightRecording=filename=%s/profile-%%p.jfr,dumponexit=true,settings=%s";
    private static final String BENCHMARK_SUMMARY_PATH_FORMAT = "benchmarks/%s/benchmark-summary.json";

    // Default values
    private static final String DEFAULT_PROFILE_MODE = "invariant";
    private static final String TOOL_NAME = "gcobs";
    private static final String TOOL_VERSION = "0.0.1";
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_FAILED = "failed";
    private static final String BENCHMARKS_DIR = "benchmarks";

    // Hash algorithm
    private static final String SHA_256 = "SHA-256";

    // JSON field names
    private static final String FIELD_GC_OVERHEAD_PCT = "gcOverheadPct";
    private static final String FIELD_PAUSE = "pause";
    private static final String FIELD_P99_MS = "p99Ms";
    private static final String FIELD_COUNT_FULL = "countFull";
    private static final String FIELD_JMH = "jmh";
    private static final String FIELD_SCORE = "score";
    private static final String FIELD_SCORE_UNIT = "scoreUnit";
    private static final String FIELD_SCORE_ERROR = "scoreError";

    // Report formatting
    private static final String REPORT_NA = "N/A";
    private static final String REPORT_HEADER = "# GC Observatory Run Report\n\n";
    private static final String REPORT_TABLE_HEADER = "| Field | Value |\n|-------|-------|\n";
    private static final String REPORT_BENCHMARK_HEADER = "## Benchmark Results\n\n";
    private static final String REPORT_BENCHMARK_TABLE_HEADER = "| ID | Status | JMH Score | GC Overhead | P99 Pause | Full GC |\n|----|--------|-----------|-------------|-----------|--------|\n";
    private static final String REPORT_SEPARATOR = "\n---\n\n";

    // Format patterns
    private static final String FORMAT_PERCENTAGE = "%.2f%%";
    private static final String FORMAT_MILLISECONDS = "%.1fms";
    private static final String FORMAT_SCORE = "%.4f%s";

    public void writeBenchmarkSummary(BenchmarkContext ctx) throws IOException {
        JmhScore jmhScore = JmhResultParser.parse(ctx.benchDir());
        EffectiveBenchmarkConfig config = ctx.config();

        BenchmarkSummaryModel summaryModel = BenchmarkSummaryModel.builder()
                .benchmarkId(ctx.benchmarkId())
                .runId(ctx.runId())
                .status(ctx.status())
                .startedAt(ISO_FMT.format(ctx.startedAt()))
                .finishedAt(ISO_FMT.format(ctx.finishedAt()))
                .durationMs(ctx.durationMs())
                .source(buildSource(ctx))
                .jmh(buildJmh(config, jmhScore))
                .jmhProfilers(buildJmhProfilers())
                .jvm(buildJvm(config, ctx))
                .params(config.params())
                .gcSummaryRef(GC_SUMMARY_JSON)
                .jfrSummaryRef(config.jfrEnabled() ? JFR_SUMMARY_JSON : null)
                .environment(ctx.environment())
                .artifacts(buildArtifactsManifest(ctx.benchDir(), config.jfrEnabled()))
                .warnings(collectWarnings(ctx))
                .thresholdResult(ctx.thresholdResult())
                .diagnostics(buildDiagnostics(ctx.jfrSummary()))
                .build();

        JsonWriter.write(ctx.benchDir().resolve(BENCHMARK_SUMMARY_JSON), summaryModel);
    }

    private BenchmarkSummaryModel.Source buildSource(BenchmarkContext ctx) {
        return BenchmarkSummaryModel.Source.builder()
                .type(ctx.source().type() != null ? ctx.source().type().getKey() : null)
                .module(ctx.source().module())
                .path(ctx.source().path())
                .projectDir(ctx.source().projectDir())
                .build();
    }

    private BenchmarkSummaryModel.Jmh buildJmh(EffectiveBenchmarkConfig config, JmhScore jmhScore) {
        return BenchmarkSummaryModel.Jmh.builder()
                .includes(config.jmhIncludes())
                .warmupIterations(config.warmupIterations())
                .measurementIterations(config.measurementIterations())
                .forks(config.forks())
                .threads(config.threads())
                .score(jmhScore.score())
                .scoreError(jmhScore.scoreError())
                .scoreUnit(jmhScore.scoreUnit())
                .scoreConfidenceInterval(jmhScore.scoreConfidenceInterval())
                .build();
    }

    private BenchmarkSummaryModel.JmhProfilers buildJmhProfilers() {
        return BenchmarkSummaryModel.JmhProfilers.builder()
                .requested(Collections.emptyList())
                .effective(Collections.emptyList())
                .failed(Collections.emptyList())
                .build();
    }

    private BenchmarkSummaryModel.Jvm buildJvm(EffectiveBenchmarkConfig config, BenchmarkContext ctx) {
        List<String> requestedArgs = config.jvmArgs() != null ? config.jvmArgs() : Collections.emptyList();
        List<String> effectiveArgs = buildEffectiveJvmArgs(config, ctx);

        String gcLogFlag = String.format(GC_LOG_FLAG_FORMAT, config.gcLogTags(), ctx.benchDir().toAbsolutePath());
        String jfrFlag = config.jfrEnabled()
                ? String.format(JFR_FLAG_FORMAT, ctx.benchDir().toAbsolutePath(), config.jfrSettings())
                : null;

        return BenchmarkSummaryModel.Jvm.builder()
                .argsRequested(requestedArgs)
                .argsEffective(effectiveArgs)
                .injectedGcLogFlag(gcLogFlag)
                .injectedJfrFlag(jfrFlag)
                .jfrFiles(listFiles(ctx.benchDir(), JFR_FILE_PATTERN))
                .build();
    }

    private List<String> buildEffectiveJvmArgs(EffectiveBenchmarkConfig config, BenchmarkContext ctx) {
        List<String> effective = new ArrayList<>();

        effective.add(String.format(GC_LOG_FLAG_FORMAT, config.gcLogTags(), ctx.benchDir().toAbsolutePath()));

        if (config.jfrEnabled()) {
            effective.add(String.format(JFR_FLAG_FORMAT, ctx.benchDir().toAbsolutePath(), config.jfrSettings()));
        }

        if (config.jvmArgs() != null) {
            effective.addAll(config.jvmArgs());
        }

        return effective;
    }

    private List<String> collectWarnings(BenchmarkContext ctx) {
        List<String> warnings = new ArrayList<>();

        if (ctx.gcSummary() != null && ctx.gcSummary().warnings() != null) {
            warnings.addAll(ctx.gcSummary().warnings());
        }

        if (ctx.jfrSummary() != null && ctx.jfrSummary().warnings() != null) {
            warnings.addAll(ctx.jfrSummary().warnings());
        }

        if (ctx.rigorWarnings() != null) {
            warnings.addAll(ctx.rigorWarnings());
        }

        return warnings.isEmpty() ? null : warnings;
    }

    /**
     * Builds the diagnostics block from JFR data.
     */
    private static DiagnosticsModel buildDiagnostics(JfrSummary jfr) {
        if (jfr == null) return null;

        boolean hasAllocation = jfr.allocationProfile() != null
                && jfr.allocationProfile().topClassesByCount() != null
                && !jfr.allocationProfile().topClassesByCount().isEmpty();
        boolean hasCompilation = jfr.compilation() != null;
        boolean hasContention = jfr.contention() != null;

        if (!hasAllocation && !hasCompilation && !hasContention) return null;

        DiagnosticsModel.DiagnosticsModelBuilder diagnosticsModelBuilder = DiagnosticsModel.builder();

        if (hasAllocation) {
            diagnosticsModelBuilder.allocationHotspots(jfr.allocationProfile().topClassesByCount());
        }

        if (hasCompilation) {
            DiagnosticsModel.CompilationInterference.CompilationInterferenceBuilder compilationInterferenceBuilder = DiagnosticsModel.CompilationInterference.builder()
                    .compilationsTotal(jfr.compilation().count())
                    .osrCompilations(jfr.compilation().osrCount())
                    .longestCompilationMs(jfr.compilation().maxMs());
            if (jfr.compilation().osrCount() > 0) {
                compilationInterferenceBuilder.note(String.format(
                        "%d OSR compilations detected during measurement, may indicate insufficient warmup",
                        jfr.compilation().osrCount()));
            }
            diagnosticsModelBuilder.compilationInterference(compilationInterferenceBuilder.build());
        }

        if (hasContention) {
            DiagnosticsModel.ThreadContention.ThreadContentionBuilder threadContentionBuilder = DiagnosticsModel.ThreadContention.builder()
                    .monitorEvents(jfr.contention().monitorEvents())
                    .parkEvents(jfr.contention().parkEvents());
            if (jfr.contention().monitorEvents() > 10) {
                threadContentionBuilder.note(String.format(
                        "%d monitor contention events detected, may indicate lock contention affecting results",
                        jfr.contention().monitorEvents()));
            }
            diagnosticsModelBuilder.threadContention(threadContentionBuilder.build());
        }

        return diagnosticsModelBuilder.build();
    }

    public void writeRunManifest(RunContext ctx, RunSummaryContext summaryCtx) throws IOException {
        // Spec
        RunManifestModel.Spec spec = RunManifestModel.Spec.builder()
                .path(RUN_SPEC_YAML)
                .sha256(computeSha256(ctx.specPath()))
                .name(ctx.spec().metadata().name())
                .build();

        // Profile
        String mode = (ctx.spec().run() != null && ctx.spec().run().profile() != null)
                ? ctx.spec().run().profile() : DEFAULT_PROFILE_MODE;
        RunManifestModel.Profile profile = RunManifestModel.Profile.builder()
                .mode(mode)
                .comparable(DEFAULT_PROFILE_MODE.equals(mode))
                .build();

        // Benchmarks array
        List<RunManifestModel.BenchmarkEntry> entries = new ArrayList<>();
        for (var result : ctx.results()) {
            // Read metrics from gc-summary.json and benchmark-summary.json
            Path benchDir = ctx.runDir().resolve(BENCHMARKS_DIR).resolve(result.benchmarkId());
            RunManifestModel.BenchmarkEntry entry = populateBenchmarkMetrics(result, benchDir);

            entries.add(entry);
        }

        // Threshold summary

        List<RunManifestModel.ThresholdBreach> allBreaches = new ArrayList<>();
        boolean allPassed = true;
        for (var result : ctx.results()) {
            var thresholdResult = result.thresholdResult();
            if (thresholdResult != null && FAIL.equals(thresholdResult.status()) && thresholdResult.breaches() != null) {
                allPassed = false;
                for (var b : thresholdResult.breaches()) {
                    allBreaches.add(new RunManifestModel.ThresholdBreach(
                            result.benchmarkId(), b.field(), b.threshold(), b.actual(), b.message()));
                }
            }
        }

        RunManifestModel.ThresholdSummary thresholdSummary = RunManifestModel.ThresholdSummary.builder()
                .allPassed(allPassed)
                .breaches(allBreaches)
                .build();

        // Execution
        int benchmarksTotal = ctx.results().size();
        int benchmarksSuccess = (int) ctx.results().stream().filter(BenchmarkResult::isSuccess).count();
        RunManifestModel.Execution exec = RunManifestModel.Execution.builder()
                .exitCode(ctx.exitCode())
                .durationMs(Duration.between(ctx.startedAt(), ctx.finishedAt()).toMillis())
                .status(ctx.exitCode() == 0 ? STATUS_SUCCESS : STATUS_FAILED)
                .benchmarksTotal(benchmarksTotal)
                .benchmarksSuccess(benchmarksSuccess)
                .benchmarksFailed(benchmarksTotal - benchmarksSuccess)
                .build();

        RunManifestModel manifest = RunManifestModel.builder()
                .runId(ctx.runId())
                .createdAt(ISO_FMT.format(ctx.finishedAt()))
                .tool(new RunManifestModel.Tool(TOOL_NAME, TOOL_VERSION))
                .spec(spec)
                .profile(profile)
                .benchmarks(entries)
                .comparisons(summaryCtx.comparisons())
                .thresholdSummary(thresholdSummary)
                .execution(exec)
                .warnings(Collections.emptyList())
                .environment(ctx.environment())
                .build();

        JsonWriter.write(ctx.runDir().resolve(RUN_JSON), manifest);
    }

    public void writeReport(RunContext ctx) throws IOException {
        StringBuilder sb = new StringBuilder();

        appendReportHeader(sb, ctx);
        appendBenchmarkResults(sb, ctx);
        appendReportFooter(sb, ctx);

        Files.writeString(ctx.runDir().resolve(REPORT_MD), sb.toString());
    }

    private void appendReportHeader(StringBuilder sb, RunContext ctx) {
        String mode = (ctx.spec().run() != null && ctx.spec().run().profile() != null)
                ? ctx.spec().run().profile() : DEFAULT_PROFILE_MODE;
        long durationSecs = Duration.between(ctx.startedAt(), ctx.finishedAt()).getSeconds();
        long succeeded = ctx.results().stream().filter(BenchmarkResult::isSuccess).count();

        sb.append(REPORT_HEADER);
        sb.append(REPORT_TABLE_HEADER);
        sb.append("| Run ID | ").append(ctx.runId()).append(" |\n");
        sb.append("| Spec | ").append(ctx.spec().metadata().name()).append(" |\n");
        sb.append("| Profile | ").append(mode).append(" |\n");
        sb.append("| Date | ").append(ISO_FMT.format(ctx.startedAt())).append(" |\n");
        sb.append("| Duration | ").append(durationSecs).append("s |\n");
        sb.append("| Benchmarks | ").append(succeeded).append("/").append(ctx.results().size())
                .append(" succeeded |\n");
        sb.append("| Exit Code | ").append(ctx.exitCode()).append(" |\n");
        sb.append("\n");
    }

    private void appendBenchmarkResults(StringBuilder sb, RunContext ctx) {
        sb.append(REPORT_BENCHMARK_HEADER);
        sb.append(REPORT_BENCHMARK_TABLE_HEADER);

        for (var result : ctx.results()) {
            Path benchDir = ctx.runDir().resolve(BENCHMARKS_DIR).resolve(result.benchmarkId());
            BenchmarkGcMetrics gcMetrics = extractGcMetrics(benchDir);
            BenchmarkJmhMetrics jmhMetrics = extractJmhMetrics(benchDir);

            sb.append("| ").append(result.benchmarkId())
                    .append(" | ").append((result.isSuccess() ? STATUS_SUCCESS : STATUS_FAILED).toUpperCase())
                    .append(" | ").append(getOrNotAvailable(jmhMetrics.jmhScore))
                    .append(" | ").append(gcMetrics.gcOverhead)
                    .append(" | ").append(gcMetrics.p99Pause)
                    .append(" | ").append(gcMetrics.fullGc)
                    .append(" |\n");
        }
    }

    private String getOrNotAvailable(String jmhScore) {
        return jmhScore != null ? jmhScore : REPORT_NA;
    }

    private void appendReportFooter(StringBuilder sb, RunContext ctx) {
        sb.append(REPORT_SEPARATOR);
        sb.append("Artifacts: `").append(ctx.runDir()).append("/`\n");
    }

    private BenchmarkGcMetrics extractGcMetrics(Path benchDir) {
        BenchmarkGcMetrics.BenchmarkGcMetricsBuilder metricsBuilder = BenchmarkGcMetrics.builder();
        try {


            Path gcSummaryPath = benchDir.resolve(GC_SUMMARY_JSON);
            if (!Files.exists(gcSummaryPath)) {
                return metricsBuilder.build();
            }

            JsonNode gc = JsonWriter.mapper().readTree(gcSummaryPath.toFile());
            if (gc.has(FIELD_GC_OVERHEAD_PCT) && !gc.get(FIELD_GC_OVERHEAD_PCT).isNull()) {
                metricsBuilder.gcOverhead(String.format(FORMAT_PERCENTAGE, gc.get(FIELD_GC_OVERHEAD_PCT).asDouble()));
            }

            JsonNode pause = gc.path(FIELD_PAUSE);
            if (pause.has(FIELD_P99_MS) && !pause.get(FIELD_P99_MS).isNull()) {
                metricsBuilder.p99Pause(String.format(FORMAT_MILLISECONDS, pause.get(FIELD_P99_MS).asDouble()));
            }
            if (pause.has(FIELD_COUNT_FULL)) {
                metricsBuilder.fullGc(String.valueOf(pause.get(FIELD_COUNT_FULL).asInt()));
            }
        } catch (Exception ignored) {
            // Best effort for report
        }
        return metricsBuilder.build();
    }

    private BenchmarkJmhMetrics extractJmhMetrics(Path benchDir) {
        BenchmarkJmhMetrics.BenchmarkJmhMetricsBuilder metricsBuilder = BenchmarkJmhMetrics.builder();
        try {
            Path bsPath = benchDir.resolve(BENCHMARK_SUMMARY_JSON);
            if (!Files.exists(bsPath)) {
                return metricsBuilder.build();
            }

            JsonNode bs = JsonWriter.mapper().readTree(bsPath.toFile());
            JsonNode jmh = bs.path(FIELD_JMH);
            if (jmh.has(FIELD_SCORE) && !jmh.get(FIELD_SCORE).isNull()) {
                String unit = jmh.has(FIELD_SCORE_UNIT) ? " " + jmh.get(FIELD_SCORE_UNIT).asText() : "";
                metricsBuilder.jmhScore(String.format(FORMAT_SCORE, jmh.get(FIELD_SCORE).asDouble(), unit));
            }
        } catch (Exception ignored) {
            // Best effort for report
        }
        return metricsBuilder.build();
    }

    @Builder
    private record BenchmarkGcMetrics(String gcOverhead, String p99Pause, String fullGc) {
    }

    @Builder
    private record BenchmarkJmhMetrics(String jmhScore) {
    }

    // --- Private helpers ---

    private RunManifestModel.BenchmarkEntry populateBenchmarkMetrics(BenchmarkResult result, Path benchDir) {

        RunManifestModel.BenchmarkEntry.BenchmarkEntryBuilder benchmarkEntryBuilder = RunManifestModel.BenchmarkEntry.builder()
                .id(result.benchmarkId())
                .status(result.isSuccess() ? STATUS_SUCCESS : STATUS_FAILED)
                .durationMs(result.duration().toMillis())
                .thresholdsPassed(null)
                .summaryPath(String.format(BENCHMARK_SUMMARY_PATH_FORMAT, result.benchmarkId()));

        if(result.thresholdResult() != null) {
            benchmarkEntryBuilder.thresholdsPassed(PASS.equals(result.thresholdResult().status()));
        }

        try {
            Path gcSummaryPath = benchDir.resolve(GC_SUMMARY_JSON);
            if (Files.exists(gcSummaryPath)) {
                GcSummary gc = JsonWriter.mapper().readValue(gcSummaryPath.toFile(), GcSummary.class);
                benchmarkEntryBuilder.gcOverheadPct(gc.gcOverheadPct());
                if (gc.pause() != null) {
                    benchmarkEntryBuilder.gcPauseP99Ms(gc.pause().p99Ms());
                    benchmarkEntryBuilder.gcCountFull(gc.pause().countFull());
                }
            }

            Path bsPath = benchDir.resolve(BENCHMARK_SUMMARY_JSON);
            if (Files.exists(bsPath)) {
                JsonNode bs = JsonWriter.mapper().readTree(bsPath.toFile());
                JsonNode jmh = bs.path(FIELD_JMH);
                if (jmh.has(FIELD_SCORE) && !jmh.get(FIELD_SCORE).isNull()) {
                    benchmarkEntryBuilder.jmhScore(jmh.get(FIELD_SCORE).asDouble());
                }
                if (jmh.has(FIELD_SCORE_UNIT) && !jmh.get(FIELD_SCORE_UNIT).isNull()) {
                    benchmarkEntryBuilder.jmhScoreUnit(jmh.get(FIELD_SCORE_UNIT).asText());
                }
                if (jmh.has(FIELD_SCORE_ERROR) && !jmh.get(FIELD_SCORE_ERROR).isNull()) {
                    benchmarkEntryBuilder.jmhScoreError(jmh.get(FIELD_SCORE_ERROR).asDouble());
                }
            }
        } catch (Exception e) {
            log.error("WARNING: Could not read metrics for %s: %s%n",
                    result.benchmarkId(), e.getMessage());
        }
        return benchmarkEntryBuilder.build();
    }

    private BenchmarkSummaryModel.Artifacts buildArtifactsManifest(Path benchDir, boolean jfrEnabled) {
        return BenchmarkSummaryModel.Artifacts.builder()
                .gcLogs(listFiles(benchDir, GC_LOG_PATTERN))
                .gcLog(fileIfExists(benchDir, GC_LOG))
                .gcSummary(fileIfExists(benchDir, GC_SUMMARY_JSON))
                .gcSummaryWarmup(fileIfExists(benchDir, GC_SUMMARY_WARMUP_JSON))
                .jfrFiles(jfrEnabled ? listFiles(benchDir, JFR_FILE_PATTERN) : null)
                .jfrSummary(jfrEnabled ? fileIfExists(benchDir, JFR_SUMMARY_JSON) : null)
                .jmhResultsJson(fileIfExists(benchDir, JMH_RESULTS_JSON))
                .jmhResultsCsv(fileIfExists(benchDir, JMH_RESULTS_CSV))
                .cmdlineTxt(fileIfExists(benchDir, JMH_CMDLINE_TXT))
                .stdout(fileIfExists(benchDir, JMH_STDOUT_LOG))
                .stderr(fileIfExists(benchDir, JMH_STDERR_LOG))
                .timeseries(fileIfExists(benchDir, METRICS_TIMESERIES_JSONL))
                .build();
    }

    private String fileIfExists(Path dir, String filename) {
        return Files.exists(dir.resolve(filename)) ? filename : null;
    }

    private List<String> listFiles(Path dir, String regex) {
        try (var stream = Files.list(dir)) {
            List<String> files = stream
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.matches(regex))
                    .sorted()
                    .toList();
            return files.isEmpty() ? null : files;
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    private String computeSha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance(SHA_256);
            byte[] hash = md.digest(Files.readAllBytes(file));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(SHA_256 + " not available", e);
        }
    }
}