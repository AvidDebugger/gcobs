package net.szumigaj.gcobs.cli.artifacts;

import com.fasterxml.jackson.databind.JsonNode;
import net.szumigaj.gcobs.cli.artifact.ArtifactWriter;
import net.szumigaj.gcobs.cli.artifact.BenchmarkContext;
import net.szumigaj.gcobs.cli.artifact.RunContext;
import net.szumigaj.gcobs.cli.executor.BenchmarkResult;
import net.szumigaj.gcobs.cli.model.config.*;
import net.szumigaj.gcobs.cli.model.config.SourceType;
import net.szumigaj.gcobs.cli.model.env.*;
import net.szumigaj.gcobs.cli.model.result.*;
import net.szumigaj.gcobs.cli.spec.EffectiveBenchmarkConfig;
import net.szumigaj.gcobs.cli.telemetry.JsonWriter;
import net.szumigaj.gcobs.cli.threshold.ThresholdResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactWriterTest {

    @TempDir
    private Path tempDir;

    private final ArtifactWriter artifactWriter = new ArtifactWriter();

    @Test
    void writeBenchmarkSummary() throws IOException {
        Path benchDir = tempDir.resolve("benchmarks/g1-test");
        Files.createDirectories(benchDir);

        // Write a JMH results fixture
        try (InputStream in = getClass().getResourceAsStream("/jmh/jmh-results-sample.json")) {
            Files.copy(in, benchDir.resolve("jmh-results.json"));
        }

        GcSummary gc = GcSummary.builder()
                .benchmarkId("g1-test")
                .runId("test-run")
                .gcAlgorithm("G1")
                .gcOverheadPct(4.5)
                .pause(PauseStats.builder()
                        .countTotal(10)
                        .countMinor(8)
                        .countFull(0)
                        .p99Ms(42.0)
                        .build())
                .build();
        JsonWriter.write(benchDir.resolve("gc-summary.json"), gc);

        // Write jmh.cmdline.txt, stdout, stderr
        Files.writeString(benchDir.resolve("jmh.cmdline.txt"), "test command");
        Files.writeString(benchDir.resolve("jmh.stdout.log"), "stdout");
        Files.writeString(benchDir.resolve("jmh.stderr.log"), "stderr");

        ThresholdResult thresholdResult = ThresholdResult.builder()
                .status(ThresholdResult.ThresholdStatus.SKIPPED)
                .build();

        BenchmarkContext ctx = createBenchmarkContext(benchDir, "g1-test", "success", thresholdResult);


        artifactWriter.writeBenchmarkSummary(ctx);

        // Verify benchmark-summary.json
        Path summaryPath = benchDir.resolve("benchmark-summary.json");
        assertThat(summaryPath).exists();

        JsonNode root = JsonWriter.mapper().readTree(summaryPath.toFile());
        assertThat(root.get("benchmarkId").asText()).isEqualTo("g1-test");
        assertThat(root.get("runId").asText()).isEqualTo("test-run");
        assertThat(root.get("status").asText()).isEqualTo("success");
        assertThat(root.has("startedAt")).isTrue();
        assertThat(root.has("finishedAt")).isTrue();
        assertThat(root.get("durationMs").asLong()).isGreaterThan(0);

        // JMH score from fixture
        JsonNode jmh = root.get("jmh");
        assertThat(jmh.get("score").asDouble()).isGreaterThan(0);
        assertThat(jmh.get("scoreUnit").asText()).isEqualTo("ms/op");

        // Source
        assertThat(root.get("source").get("type").asText()).isEqualTo("internal");
        assertThat(root.get("source").get("module").asText()).isEqualTo("benchmark-batch-jmh");

        // Refs
        assertThat(root.get("gcSummaryRef").asText()).isEqualTo("gc-summary.json");

        // Artifacts manifest
        JsonNode artifacts = root.get("artifacts");
        assertThat(artifacts.get("jmhResultsJson").asText()).isEqualTo("jmh-results.json");
        assertThat(artifacts.get("cmdlineTxt").asText()).isEqualTo("jmh.cmdline.txt");

        assertThat(root.get("thresholdResult").get("status").asText()).isEqualTo("SKIPPED");
        assertThat(root.has("diagnostics")).isFalse();
    }

    @Test
    void writeBenchmarkSummaryForFailedBenchmark() throws IOException {
        Path benchDir = tempDir.resolve("benchmarks/failed-test");
        Files.createDirectories(benchDir);

        BenchmarkContext ctx = createBenchmarkContext(benchDir, "failed-test", "failed", null);
        artifactWriter.writeBenchmarkSummary(ctx);

        JsonNode root = JsonWriter.mapper().readTree(
                benchDir.resolve("benchmark-summary.json").toFile());
        assertThat(root.get("status").asText()).isEqualTo("failed");
        // JMH score should be null (absent)
        assertThat(root.get("jmh").has("score")).isFalse();
    }

    @Test
    void writeBenchmarkSummary_includesRigorWarnings() throws IOException {
        Path benchDir = tempDir.resolve("benchmarks/rigor-test");
        Files.createDirectories(benchDir);

        List<String> warnings = List.of(
                "JMH forks (1) is below recommended minimum (2). Run-to-run variance will not be captured.");
        BenchmarkContext ctx = createBenchmarkContextWithRigorWarnings(benchDir, "rigor-test", "success", warnings);
        artifactWriter.writeBenchmarkSummary(ctx);

        JsonNode root = JsonWriter.mapper().readTree(benchDir.resolve("benchmark-summary.json").toFile());
        JsonNode warningsNode = root.get("warnings");
        assertThat(warningsNode).isNotNull();
        assertThat(warningsNode.isArray()).isTrue();
        assertThat(warningsNode.get(0).asText()).contains("forks (1) is below recommended minimum (2)");
    }

    @Test
    void writeReportProducesMarkdown() throws IOException {
        Path runDir = tempDir.resolve("runs/test-run");
        Files.createDirectories(runDir.resolve("benchmarks/g1-test"));

        Path specPath = tempDir.resolve("test-spec.yaml");

        RunContext ctx = createRunContext(runDir, specPath);
        artifactWriter.writeReport(ctx);

        Path reportPath = runDir.resolve("report.md");
        assertThat(reportPath).exists();

        String content = Files.readString(reportPath);
        assertThat(content).contains("# GC Observatory Run Report")
                .contains("test-run")
                .contains("test-spec")
                .contains("| ID | Status |")
                .contains("g1-test");
    }

    // --- Helpers ---

    private BenchmarkContext createBenchmarkContextWithRigorWarnings(Path benchDir, String id, String status, List<String> rigorWarnings) {
        SourceConfig source = SourceConfig.builder()
                .type(SourceType.INTERNAL)
                .module("benchmark-batch-jmh")
                .build();

        EffectiveBenchmarkConfig config = new EffectiveBenchmarkConfig(
                id, source,
                List.of("-XX:+UseG1GC", "-Xms256m", "-Xmx256m"),
                Collections.emptyMap(),
                5, 5, 1, 1,
                ".*batchKernelChecksum.*",
                Map.of("iterations", "10"),
                false, null, "gc*,safepoint*,gc+promotion",
                false, false, null, false, null
        );

        EnvironmentInfo env = EnvironmentInfo.builder()
                .javaVersion("17.0.1")
                .osName("Linux")
                .availableProcessors(8)
                .build();

        Instant start = Instant.now().minusSeconds(30);
        Instant end = Instant.now();

        return new BenchmarkContext(id, "test-run", status,
                start, end, Duration.between(start, end).toMillis(),
                config, source, null, null, env, benchDir, null, rigorWarnings);
    }

    private BenchmarkContext createBenchmarkContext(Path benchDir, String id, String status, ThresholdResult thresholdResult) {
        SourceConfig source = SourceConfig.builder()
                .type(SourceType.INTERNAL)
                .module("benchmark-batch-jmh")
                .build();

        EffectiveBenchmarkConfig config = new EffectiveBenchmarkConfig(
                id, source,
                List.of("-XX:+UseG1GC", "-Xms256m", "-Xmx256m"),
                Collections.emptyMap(),
                5, 5, 3, 1,
                ".*batchKernelChecksum.*",
                Map.of("iterations", "10"),
                true, "profile", "gc*,safepoint*,gc+promotion",
                false, false, null, false, null
        );

        EnvironmentInfo env = EnvironmentInfo.builder()
                .javaVersion("17.0.1")
                .osName("Linux")
                .availableProcessors(8)
                .build();

        Instant start = Instant.now().minusSeconds(30);
        Instant end = Instant.now();

        return new BenchmarkContext(id, "test-run", status,
                start, end, Duration.between(start, end).toMillis(),
                config, source, null, null, env, benchDir, thresholdResult, null);
    }

    @Test
    void writeBenchmarkSummary_noDiagnosticsWhenAllJfrDataAbsent() throws IOException {
        Path benchDir = tempDir.resolve("benchmarks/diag-empty");
        Files.createDirectories(benchDir);

        JfrSummary jfr = JfrSummary.builder().benchmarkId("diag-empty").runId("test-run").build();
        BenchmarkContext ctx = createBenchmarkContextWithJfr(benchDir, "diag-empty", jfr);
        artifactWriter.writeBenchmarkSummary(ctx);

        JsonNode root = JsonWriter.mapper().readTree(benchDir.resolve("benchmark-summary.json").toFile());
        assertThat(root.has("diagnostics")).isFalse();
    }

    @Test
    void writeBenchmarkSummary_diagnosticsIncludesAllocationHotspots() throws IOException {
        Path benchDir = tempDir.resolve("benchmarks/diag-alloc");
        Files.createDirectories(benchDir);

        AllocationProfile profile = AllocationProfile.builder()
                .topClassesByCount(List.of(new AllocationClassEntry("java.lang.byte[]", 500, 1_024_000L)))
                .build();
        JfrSummary jfr = JfrSummary.builder().benchmarkId("diag-alloc").runId("test-run")
                .allocationProfile(profile).build();
        BenchmarkContext ctx = createBenchmarkContextWithJfr(benchDir, "diag-alloc", jfr);
        artifactWriter.writeBenchmarkSummary(ctx);

        JsonNode root = JsonWriter.mapper().readTree(benchDir.resolve("benchmark-summary.json").toFile());
        JsonNode hotspots = root.get("diagnostics").get("allocationHotspots");
        assertThat(hotspots.isArray()).isTrue();
        assertThat(hotspots.size()).isEqualTo(1);
        assertThat(hotspots.get(0).get("className").asText()).isEqualTo("java.lang.byte[]");
        assertThat(hotspots.get(0).get("count").asInt()).isEqualTo(500);
        assertThat(hotspots.get(0).get("totalBytes").asLong()).isEqualTo(1_024_000L);
    }

    @Test
    void writeBenchmarkSummary_compilationInterference_noNoteWhenOsrCountZero() throws IOException {
        Path benchDir = tempDir.resolve("benchmarks/diag-comp-no-osr");
        Files.createDirectories(benchDir);

        JfrSummary jfr = JfrSummary.builder().benchmarkId("diag-comp-no-osr").runId("test-run")
                .compilation(Compilation.builder().count(200).osrCount(0).maxMs(12.5).totalMs(1000.0).build())
                .build();
        BenchmarkContext ctx = createBenchmarkContextWithJfr(benchDir, "diag-comp-no-osr", jfr);
        artifactWriter.writeBenchmarkSummary(ctx);

        JsonNode root = JsonWriter.mapper().readTree(benchDir.resolve("benchmark-summary.json").toFile());
        JsonNode ci = root.get("diagnostics").get("compilationInterference");
        assertThat(ci.get("compilationsTotal").asInt()).isEqualTo(200);
        assertThat(ci.get("osrCompilations").asInt()).isZero();
        assertThat(ci.has("note")).isFalse();
    }

    @Test
    void writeBenchmarkSummary_compilationInterference_withNoteWhenOsrCountPositive() throws IOException {
        Path benchDir = tempDir.resolve("benchmarks/diag-comp-osr");
        Files.createDirectories(benchDir);

        JfrSummary jfr = JfrSummary.builder().benchmarkId("diag-comp-osr").runId("test-run")
                .compilation(Compilation.builder().count(200).osrCount(5).maxMs(12.5).totalMs(1000.0).build())
                .build();
        BenchmarkContext ctx = createBenchmarkContextWithJfr(benchDir, "diag-comp-osr", jfr);
        artifactWriter.writeBenchmarkSummary(ctx);

        JsonNode root = JsonWriter.mapper().readTree(benchDir.resolve("benchmark-summary.json").toFile());
        JsonNode note = root.get("diagnostics").get("compilationInterference").get("note");
        assertThat(note.asText()).contains("5 OSR compilations");
    }

    @Test
    void writeBenchmarkSummary_threadContention_noNoteWhenMonitorEventsAtThreshold() throws IOException {
        Path benchDir = tempDir.resolve("benchmarks/diag-contention-low");
        Files.createDirectories(benchDir);

        JfrSummary jfr = JfrSummary.builder().benchmarkId("diag-contention-low").runId("test-run")
                .contention(Contention.builder().monitorEvents(10).parkEvents(50).build())
                .build();
        BenchmarkContext ctx = createBenchmarkContextWithJfr(benchDir, "diag-contention-low", jfr);
        artifactWriter.writeBenchmarkSummary(ctx);

        JsonNode root = JsonWriter.mapper().readTree(benchDir.resolve("benchmark-summary.json").toFile());
        JsonNode tc = root.get("diagnostics").get("threadContention");
        assertThat(tc.get("monitorEvents").asInt()).isEqualTo(10);
        assertThat(tc.has("note")).isFalse();
    }

    @Test
    void writeBenchmarkSummary_threadContention_withNoteWhenMonitorEventsExceedThreshold() throws IOException {
        Path benchDir = tempDir.resolve("benchmarks/diag-contention-high");
        Files.createDirectories(benchDir);

        JfrSummary jfr = JfrSummary.builder().benchmarkId("diag-contention-high").runId("test-run")
                .contention(Contention.builder().monitorEvents(11).parkEvents(50).build())
                .build();
        BenchmarkContext ctx = createBenchmarkContextWithJfr(benchDir, "diag-contention-high", jfr);
        artifactWriter.writeBenchmarkSummary(ctx);

        JsonNode root = JsonWriter.mapper().readTree(benchDir.resolve("benchmark-summary.json").toFile());
        JsonNode note = root.get("diagnostics").get("threadContention").get("note");
        assertThat(note.asText()).contains("11 monitor contention events");
    }

    @Test
    void writeBenchmarkSummary_diagnosticsAllSectionsPresent() throws IOException {
        Path benchDir = tempDir.resolve("benchmarks/diag-all");
        Files.createDirectories(benchDir);

        AllocationProfile profile = AllocationProfile.builder()
                .topClassesByCount(List.of(new AllocationClassEntry("java.lang.String", 100, 2_000L)))
                .build();
        JfrSummary jfr = JfrSummary.builder().benchmarkId("diag-all").runId("test-run")
                .allocationProfile(profile)
                .compilation(Compilation.builder().count(150).osrCount(3).maxMs(8.0).totalMs(500.0).build())
                .contention(Contention.builder().monitorEvents(20).parkEvents(30).build())
                .build();
        BenchmarkContext ctx = createBenchmarkContextWithJfr(benchDir, "diag-all", jfr);
        artifactWriter.writeBenchmarkSummary(ctx);

        JsonNode root = JsonWriter.mapper().readTree(benchDir.resolve("benchmark-summary.json").toFile());
        JsonNode diagnostics = root.get("diagnostics");
        assertThat(diagnostics.has("allocationHotspots")).isTrue();
        assertThat(diagnostics.has("compilationInterference")).isTrue();
        assertThat(diagnostics.has("threadContention")).isTrue();
    }

    private BenchmarkContext createBenchmarkContextWithJfr(Path benchDir, String id, JfrSummary jfr) {
        SourceConfig source = SourceConfig.builder()
                .type(SourceType.INTERNAL)
                .module("benchmark-batch-jmh")
                .build();

        EffectiveBenchmarkConfig config = new EffectiveBenchmarkConfig(
                id, source,
                List.of("-XX:+UseG1GC", "-Xms256m", "-Xmx256m"),
                Collections.emptyMap(),
                5, 5, 3, 1,
                ".*batchKernelChecksum.*",
                Map.of("iterations", "10"),
                true, "profile", "gc*,safepoint*,gc+promotion",
                false, false, null, false, null
        );

        EnvironmentInfo env = EnvironmentInfo.builder()
                .javaVersion("17.0.1")
                .osName("Linux")
                .availableProcessors(8)
                .build();

        Instant start = Instant.now().minusSeconds(30);
        Instant end = Instant.now();

        return new BenchmarkContext(id, "test-run", "success",
                start, end, Duration.between(start, end).toMillis(),
                config, source, null, jfr, env, benchDir, null, null);
    }

    private RunContext createRunContext(Path runDir, Path specPath) {
        BenchmarkRunSpec spec = BenchmarkRunSpec.builder()
                .metadata(Metadata.builder()
                        .name("test-spec")
                        .build())
                .run(RunConfig.builder()
                        .profile("invariant")
                        .build())
                .build();

        EnvironmentInfo env = EnvironmentInfo.builder()
                .javaVersion("17.0.1")
                .osName("Linux")
                .build();

        List<BenchmarkResult> results = List.of(
                new BenchmarkResult("g1-test", 0, Duration.ofSeconds(30), null)
        );

        Instant start = Instant.now().minusSeconds(60);
        return new RunContext("test-run", start, Instant.now(),
                specPath, spec, results, env, runDir, 0);
    }
}
