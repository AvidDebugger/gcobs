package net.szumigaj.gcobs.cli.compare;

import net.szumigaj.gcobs.cli.model.ComparisonMetric;
import net.szumigaj.gcobs.cli.model.ComparisonPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static net.szumigaj.gcobs.cli.compare.CompareResult.MetricDelta.Direction.LOWER_IS_BETTER;
import static net.szumigaj.gcobs.cli.compare.CompareResult.MetricDelta.ThresholdType.ABSOLUTE;
import static net.szumigaj.gcobs.cli.compare.ComparisonVerdict.*;
import static org.assertj.core.api.Assertions.assertThat;

class ComparisonEngineTest {

    private @TempDir Path tempDir;
    
    private final ComparisonEngine comparisonEngine = new ComparisonEngine();

    // --- Intra-spec tests ---

    @Test
    void improvementVerdict() throws IOException {
        // Candidate has lower (better) values than base
        Path runDir = setupRunDir("base-bench", "cand-bench",
                gcSummary(5.0, 80.0), gcSummary(2.0, 30.0));

        ComparisonPair pair = pair("test-compare", "base-bench", "cand-bench",
                List.of(metric("gcOverheadPct", 20.0), metric("gcPauseP99Ms", 20.0)));

        CompareResult result = comparisonEngine.comparePair(pair,
                runDir,
                runDir,
                "base-bench", "cand-bench", "invariant", false);

        assertThat(result.verdict()).isEqualTo(IMPROVEMENT);
        assertThat(result.metrics()).hasSize(2);
        assertThat(result.metrics().get(0).status().name()).isEqualTo(IMPROVEMENT.name());
        assertThat(result.metrics().get(1).status().name()).isEqualTo(IMPROVEMENT.name());
    }

    @Test
    void regressionVerdict() throws IOException {
        // Candidate has higher (worse) values
        Path runDir = setupRunDir("base-bench", "cand-bench",
                gcSummary(2.0, 30.0), gcSummary(5.0, 80.0));

        ComparisonPair pair = pair("test-compare", "base-bench", "cand-bench",
                List.of(metric("gcOverheadPct", 20.0), metric("gcPauseP99Ms", 20.0)));

        CompareResult result = comparisonEngine.comparePair(pair,
                runDir,
                runDir,
                "base-bench", "cand-bench", "invariant", false);

        assertThat(result.verdict().name()).isEqualTo(REGRESSION.name());
    }

    @Test
    void okVerdict() throws IOException {
        // Candidate slightly worse but within threshold
        Path runDir = setupRunDir("base-bench", "cand-bench",
                gcSummary(5.0, 80.0), gcSummary(5.5, 85.0));

        ComparisonPair pair = pair("test-compare", "base-bench", "cand-bench",
                List.of(metric("gcOverheadPct", 20.0), metric("gcPauseP99Ms", 20.0)));

        CompareResult result = comparisonEngine.comparePair(pair,
                runDir,
                runDir,
                "base-bench", "cand-bench", "invariant", false);

        assertThat(result.verdict().name()).isEqualTo(OK.name());
    }

    @Test
    void inconclusiveWhenMissingBenchmarkDir() throws IOException {
        Path runDir = tempDir.resolve("run1");
        Files.createDirectories(runDir.resolve("benchmarks/base-bench"));
        // cand-bench directory does not exist

        ComparisonPair pair = pair("test-compare", "base-bench", "cand-bench", null);

        CompareResult result = comparisonEngine.comparePair(pair,
                runDir,
                runDir,
                "base-bench", "cand-bench", "invariant", false);

        assertThat(result.verdict().name()).isEqualTo(INCONCLUSIVE.name());
    }

    @Test
    void inconclusiveInExploreMode() throws IOException {
        Path runDir = setupRunDir("base-bench", "cand-bench",
                gcSummary(5.0, 80.0), gcSummary(2.0, 30.0));

        ComparisonPair pair = pair("test-compare", "base-bench", "cand-bench",
                List.of(metric("gcOverheadPct", 20.0)));

        CompareResult result = comparisonEngine.comparePair(pair,
                runDir,
                runDir,
                "base-bench", "cand-bench", "explore", false);

        assertThat(result.verdict().name()).isEqualTo(INCONCLUSIVE.name());
    }

    @Test
    void defaultMetricsUsedWhenNoneSpecified() throws IOException {
        Path runDir = setupRunDir("base-bench", "cand-bench",
                gcSummary(5.0, 80.0), gcSummary(2.0, 30.0));

        ComparisonPair pair = pair("test-compare", "base-bench", "cand-bench", null);

        CompareResult result = comparisonEngine.comparePair(pair,
                runDir,
                runDir,
                "base-bench", "cand-bench", "invariant", false);

        // Default metrics: gcPauseP99Ms, gcOverheadPct, jmhScore
        assertThat(result.metrics()).hasSize(3);
        assertThat(result.metrics().stream().map(m -> m.name()).toList())
                .containsExactly("gcPauseP99Ms", "gcOverheadPct", "jmhScore");
    }

    @Test
    void deltasComputedCorrectly() throws IOException {
        Path runDir = setupRunDir("base-bench", "cand-bench",
                gcSummary(10.0, 100.0), gcSummary(5.0, 50.0));

        ComparisonPair pair = pair("test-compare", "base-bench", "cand-bench",
                List.of(ComparisonMetric.builder()
                        .name("gcOverheadPct")
                        .regressionThresholdPct(20.0).build()));

        CompareResult result = comparisonEngine.comparePair(pair,
                runDir,
                runDir,
                "base-bench", "cand-bench", "invariant", false);

        CompareResult.MetricDelta md = result.metrics().get(0);
        assertThat(md.baseValue()).isEqualTo(10.0);
        assertThat(md.candidateValue()).isEqualTo(5.0);
        assertThat(md.delta()).isEqualTo(-5.0);
        assertThat(md.deltaPct()).isEqualTo(-50.0);
        assertThat(md.status().name()).isEqualTo(IMPROVEMENT.name());
        assertThat(md.direction().name()).isEqualTo(LOWER_IS_BETTER.name());
    }

    @Test
    void inconclusiveWhenBenchmarkFailed() throws IOException {
        Path runDir = tempDir.resolve("run1");
        Path baseDir = runDir.resolve("benchmarks/base-bench");
        Path candDir = runDir.resolve("benchmarks/cand-bench");
        Files.createDirectories(baseDir);
        Files.createDirectories(candDir);

        // Base succeeded, candidate failed
        writeJson(baseDir, "benchmark-summary.json", "{\"status\":\"success\"}");
        writeJson(candDir, "benchmark-summary.json", "{\"status\":\"failed\"}");

        ComparisonPair pair = pair("test-compare", "base-bench", "cand-bench",
                List.of(metric("gcOverheadPct", 20.0)));

        CompareResult result = comparisonEngine.comparePair(pair,
                runDir,
                runDir,
                "base-bench", "cand-bench", "invariant", false);

        assertThat(result.verdict().name()).isEqualTo(INCONCLUSIVE.name());
    }

    @Test
    void absoluteThresholdRegression() throws IOException {
        // Candidate is 60ms higher than base (absolute delta > 50ms threshold)
        Path runDir = setupRunDir("base-bench", "cand-bench",
                gcSummary(5.0, 40.0), gcSummary(5.0, 100.0));

        ComparisonMetric m = ComparisonMetric.builder()
                .name("gcPauseP99Ms")
                .regressionThresholdAbsolute(50.0)
                .build();

        ComparisonPair pair = pair("test-compare", "base-bench", "cand-bench", List.of(m));

        CompareResult result = comparisonEngine.comparePair(pair,
                runDir,
                runDir,
                "base-bench", "cand-bench", "invariant", false);

        assertThat(result.verdict().name()).isEqualTo(REGRESSION.name());
        assertThat(result.metrics().get(0).thresholdType().name()).isEqualTo(ABSOLUTE.name());
    }

    @Test
    void mixedImprovementAndRegression() throws IOException {
        // gcOverheadPct improved, gcPauseP99Ms regressed
        Path runDir = setupRunDirFull("base-bench", "cand-bench",
                10.0, 40.0, 5.0, 100.0);

        ComparisonPair pair = ComparisonPair.builder()
                .id("test-compare")
                .base("base-bench")
                .candidate("cand-bench")
                .description("Test comparison")
                .metrics(List.of(metric("gcOverheadPct", 20.0), metric("gcPauseP99Ms", 20.0)))
                .build();

        CompareResult result = comparisonEngine.comparePair(pair,
                runDir,
                runDir,
                "base-bench", "cand-bench", "invariant", false);

        // Regression takes precedence
        assertThat(result.verdict().name()).isEqualTo(REGRESSION.name());
    }

    // --- Environment compatibility tests ---

    @Test
    void environmentCompatCheckCpuMismatch() throws IOException {
        Path baseRunDir = tempDir.resolve("base-run");
        Path candRunDir = tempDir.resolve("cand-run");
        Files.createDirectories(baseRunDir);
        Files.createDirectories(candRunDir);

        ComparisonPair pair = pair("test-compare", "base-bench", "cand-bench",
                List.of(metric("gcOverheadPct", 20.0), metric("gcPauseP99Ms", 20.0)));

        writeJson(baseRunDir, "run.json",
                "{\"environment\":{\"availableProcessors\":8,\"javaVersion\":\"21\",\"physicalMemoryMb\":16384}}");
        writeJson(candRunDir, "run.json",
                "{\"environment\":{\"availableProcessors\":4,\"javaVersion\":\"21\",\"physicalMemoryMb\":16384}}");

        CompareResult result = comparisonEngine.comparePair(pair,
                baseRunDir,
                candRunDir,
                "run1", "run1", "invariant", true);

        List<String> warnings = result.environmentMatch().warnings();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("CPU count");
    }

    // --- Helpers ---

    private Path setupRunDir(String baseId, String candId,
                              String baseGcSummary, String candGcSummary) throws IOException {
        Path runDir = tempDir.resolve("run1");
        Path baseDir = runDir.resolve("benchmarks/" + baseId);
        Path candDir = runDir.resolve("benchmarks/" + candId);
        Files.createDirectories(baseDir);
        Files.createDirectories(candDir);

        writeJson(baseDir, "benchmark-summary.json", "{\"status\":\"success\",\"jmh\":{\"score\":0.018}}");
        writeJson(candDir, "benchmark-summary.json", "{\"status\":\"success\",\"jmh\":{\"score\":0.012}}");
        writeJson(baseDir, "gc-summary.json", baseGcSummary);
        writeJson(candDir, "gc-summary.json", candGcSummary);

        return runDir;
    }

    private Path setupRunDirFull(String baseId, String candId,
                                  double baseOverhead, double baseP99,
                                  double candOverhead, double candP99) throws IOException {
        return setupRunDir(baseId, candId,
                gcSummary(baseOverhead, baseP99),
                gcSummary(candOverhead, candP99));
    }

    private String gcSummary(double overheadPct, double pauseP99Ms) {
        return String.format(
                "{\"gcOverheadPct\":%.1f,\"pause\":{\"p99Ms\":%.1f,\"p95Ms\":%.1f,\"maxMs\":%.1f,\"countFull\":0}}",
                overheadPct, pauseP99Ms, pauseP99Ms * 0.9, pauseP99Ms * 1.2);
    }

    private ComparisonPair pair(String id, String base, String candidate,
                                 List<ComparisonMetric> metrics) {
        return ComparisonPair.builder()
                .id(id)
                .base(base)
                .candidate(candidate)
                .description("Test comparison")
                .metrics(metrics)
                .build();
    }

    private ComparisonMetric metric(String name, double regressionThresholdPct) {
        return ComparisonMetric.builder()
                .name(name)
                .regressionThresholdPct(regressionThresholdPct)
                .build();
    }

    private void writeJson(Path dir, String filename, String content) throws IOException {
        Files.writeString(dir.resolve(filename), content);
    }
}
