package net.szumigaj.gcobs.cli.artifacts;

import com.fasterxml.jackson.databind.JsonNode;
import net.szumigaj.gcobs.cli.artifact.ArtifactWriter;
import net.szumigaj.gcobs.cli.artifact.BenchmarkContext;
import net.szumigaj.gcobs.cli.artifact.RunContext;
import net.szumigaj.gcobs.cli.executor.BenchmarkResult;
import net.szumigaj.gcobs.cli.model.*;
import net.szumigaj.gcobs.cli.spec.EffectiveBenchmarkConfig;
import net.szumigaj.gcobs.cli.telemetry.JsonWriter;
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

        BenchmarkContext ctx = createBenchmarkContext(benchDir, "g1-test", "success");
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
    }

    @Test
    void writeBenchmarkSummaryForFailedBenchmark() throws IOException {
        Path benchDir = tempDir.resolve("benchmarks/failed-test");
        Files.createDirectories(benchDir);

        BenchmarkContext ctx = createBenchmarkContext(benchDir, "failed-test", "failed");
        artifactWriter.writeBenchmarkSummary(ctx);

        JsonNode root = JsonWriter.mapper().readTree(
                benchDir.resolve("benchmark-summary.json").toFile());
        assertThat(root.get("status").asText()).isEqualTo("failed");
        // JMH score should be null (absent)
        assertThat(root.get("jmh").has("score")).isFalse();
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

    private BenchmarkContext createBenchmarkContext(Path benchDir, String id, String status) {
        SourceConfig source = SourceConfig.builder()
                .type("internal")
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
                config, source, null, null, env, benchDir);
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
                new BenchmarkResult("g1-test", 0, Duration.ofSeconds(30))
        );

        Instant start = Instant.now().minusSeconds(60);
        return new RunContext("test-run", start, Instant.now(),
                specPath, spec, results, env, runDir, 0);
    }
}
