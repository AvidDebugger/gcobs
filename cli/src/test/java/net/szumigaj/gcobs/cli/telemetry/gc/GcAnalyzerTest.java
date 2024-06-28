package net.szumigaj.gcobs.cli.telemetry.gc;

import net.szumigaj.gcobs.cli.model.result.GcSummary;
import net.szumigaj.gcobs.cli.telemetry.PercentileCalculator;
import net.szumigaj.gcobs.cli.telemetry.gc.parser.G1GcLogParser;
import net.szumigaj.gcobs.cli.telemetry.gc.parser.GcLogParserDispatcher;
import net.szumigaj.gcobs.cli.telemetry.gc.parser.LegacyFallbackGcLogParser;
import net.szumigaj.gcobs.cli.telemetry.gc.parser.ZgcGcLogParser;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GcAnalyzerTest {

    @TempDir
    private Path tempDir;

    private final GcAnalyzer analyzer = new GcAnalyzer(new GcLogParserDispatcher(new G1GcLogParser(), new ZgcGcLogParser(), new LegacyFallbackGcLogParser()));

    private GcSummary analyzeLog(String logContent) throws IOException {
        Files.writeString(tempDir.resolve("gc.log"), logContent);
        return analyzer.analyze(tempDir, "test-bench", "test-run");
    }

    private GcSummary analyzeFixture(String fixture) throws IOException {
        String content = Files.readString(Path.of("src/test/resources/gc-logs/" + fixture));
        return analyzeLog(content);
    }

    // --- G1 Tests (G1GcLogParser strategy) ---

    @Test
    void g1StrategyPathProducesCorrectPauseAndCauseMetrics() throws IOException {
        // G1 logs are parsed by G1GcLogParser, verify it produces same output as before refactor
        GcSummary s = analyzeFixture("g1-sample.log");

        assertThat(s.gcAlgorithm()).isEqualTo("G1");
        assertThat(s.pause().countTotal()).isEqualTo(7);
        assertThat(s.causeBreakdown()).containsKey("G1 Evacuation Pause");
        assertThat(s.causeBreakdown()).containsKey("G1 Humongous Allocation");
    }

    @Test
    void analyzesG1LogWithAllPauseTypes() throws IOException {
        GcSummary s = analyzeFixture("g1-sample.log");

        assertThat(s.benchmarkId()).isEqualTo("test-bench");
        assertThat(s.runId()).isEqualTo("test-run");
        assertThat(s.gcAlgorithm()).isEqualTo("G1");
        assertThat(s.analysisScope()).isEqualTo("full");
        assertThat(s.runDurationMs()).isEqualTo(2000L);

        // Pause counts
        assertThat(s.pause().countTotal()).isEqualTo(7);
        assertThat(s.pause().countMinor()).isEqualTo(5);
        assertThat(s.pause().countMixed()).isEqualTo(1);
        assertThat(s.pause().countFull()).isEqualTo(1);

        // Pause stats
        assertThat(s.pause().minMs()).isEqualTo(2.1);
        assertThat(s.pause().maxMs()).isEqualTo(45.678);
        assertThat(s.pause().totalMs()).isGreaterThan(0.0);
        assertThat(s.pause().meanMs()).isGreaterThan(0.0);
        assertThat(s.pause().p50Ms()).isNotNull();
        assertThat(s.pause().p95Ms()).isNotNull();
        assertThat(s.pause().p99Ms()).isNotNull();
    }

    @Test
    void computesCauseBreakdown() throws IOException {
        GcSummary s = analyzeFixture("g1-sample.log");

        assertThat(s.causeBreakdown()).isNotNull();
        assertThat(s.causeBreakdown()).containsKey("G1 Evacuation Pause");
        assertThat(s.causeBreakdown().get("G1 Evacuation Pause").count()).isEqualTo(6);
        assertThat(s.causeBreakdown()).containsKey("G1 Humongous Allocation");
        assertThat(s.causeBreakdown().get("G1 Humongous Allocation").count()).isEqualTo(1);
    }

    @Test
    void computesGcOverheadPct() throws IOException {
        GcSummary s = analyzeFixture("g1-sample.log");

        assertThat(s.gcOverheadPct())
                .isNotNull()
                .isGreaterThan(0.0)
                .isLessThan(100.0)
                // total pause ~81.156ms / 2000ms = ~4.06%
                .isBetween(4.05, 4.07);
    }

    @Test
    void extractsSafepointTtsp() throws IOException {
        GcSummary s = analyzeFixture("g1-sample.log");

        assertThat(s.safepoint())
                .isNotNull()
                .satisfies(sp -> {
                    assertThat(sp.countTotal()).isEqualTo(3);
                    assertThat(sp.ttspMaxMs()).isGreaterThan(0.0);
                    assertThat(sp.ttspMeanMs()).isGreaterThan(0.0);
                });
    }

    @Test
    void computesHeapStats() throws IOException {
        GcSummary s = analyzeFixture("g1-sample.log");

        assertThat(s.heap())
                .isNotNull()
                .satisfies(h -> {
                    assertThat(h.peakUsedMb()).isEqualTo(128); // max beforeMb
                    assertThat(h.reclaimedTotalMb()).isGreaterThan(0);
                    assertThat(h.allocationRateMbPerSec()).isGreaterThan(0.0);
                });
    }

    @Test
    void collectionsArrayPopulated() throws IOException {
        GcSummary s = analyzeFixture("g1-sample.log");

        assertThat(s.collections()).hasSize(7);
        assertThat(s.collections().get(0).type()).isEqualTo("Young");
        assertThat(s.collections().get(0).cause()).isEqualTo("G1 Evacuation Pause");
    }

    @Test
    void collectionEventsHaveCorrectSequenceNumbers() throws IOException {
        // Log with GC pauses, safepoints, and promotion failures interleaved
        String log = """
                [0ms] Using G1
                [100ms] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->8M(256M) 5.000ms
                [200ms] Reaching safepoint: 50000 ns
                [300ms] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 32M->12M(256M) 3.000ms
                [400ms] Promotion failed
                [500ms] GC(2) Pause Full (Allocation Failure) (G1 Evacuation Pause) 64M->16M(256M) 10.000ms
                """;
        GcSummary s = analyzeLog(log);

        assertThat(s.collections()).hasSize(3);
        assertThat(s.collections().get(0).n()).isEqualTo(1);
        assertThat(s.collections().get(1).n()).isEqualTo(2);
        assertThat(s.collections().get(2).n()).isEqualTo(3);
    }

    // --- ZGC Tests (ZgcGcLogParser strategy) ---

    @Test
    void dedicatedParsersHandleNonFallbackLogs() throws IOException {
        // ZGC uses ZgcGcLogParser, Parallel uses LegacyFallbackGcLogParser
        GcSummary zgc = analyzeFixture("zgc-sample.log");
        assertThat(zgc.gcAlgorithm()).isEqualTo("ZGC");
        assertThat(zgc.pause().countTotal()).isEqualTo(9);

        GcSummary parallel = analyzeFixture("parallel-sample.log");
        assertThat(parallel.gcAlgorithm()).isEqualTo("Parallel");
        assertThat(parallel.pause().countTotal()).isEqualTo(4);
    }

    @Test
    void analyzesZgcLog() throws IOException {
        GcSummary s = analyzeFixture("zgc-sample.log");

        assertThat(s.gcAlgorithm()).isEqualTo("ZGC");
        assertThat(s.pause().countTotal()).isEqualTo(9);
        assertThat(s.pause().countMinor()).isEqualTo(9);
        assertThat(s.pause().maxMs()).isLessThan(1.0); // sub-millisecond
        assertThat(s.collections()).hasSize(9);
        assertThat(s.collections().get(0).type()).isEqualTo("STW-Minor");
    }

    @Test
    void zgcCauseBreakdownReflectsPhases() throws IOException {
        GcSummary s = analyzeFixture("zgc-sample.log");

        assertThat(s.causeBreakdown()).containsKey("Mark Start");
        assertThat(s.causeBreakdown()).containsKey("Mark End");
        assertThat(s.causeBreakdown()).containsKey("Relocate Start");
        assertThat(s.causeBreakdown().get("Mark Start").count()).isEqualTo(3);
        assertThat(s.causeBreakdown().get("Mark End").count()).isEqualTo(3);
        assertThat(s.causeBreakdown().get("Relocate Start").count()).isEqualTo(3);
    }

    // --- Parallel Tests ---

    @Test
    void analyzesParallelLog() throws IOException {
        GcSummary s = analyzeFixture("parallel-sample.log");

        assertThat(s.gcAlgorithm()).isEqualTo("Parallel");
        assertThat(s.pause().countTotal()).isEqualTo(4);
        assertThat(s.pause().countMinor()).isEqualTo(3);
        assertThat(s.pause().countFull()).isEqualTo(1);
    }

    // --- Epsilon Tests ---

    @Test
    void handlesEpsilonGc() throws IOException {
        GcSummary s = analyzeFixture("epsilon-sample.log");

        assertThat(s.gcAlgorithm()).isEqualTo("Epsilon");
        assertThat(s.pause().countTotal()).isZero();
        assertThat(s.pause().totalMs()).isEqualTo(0.0);
        assertThat(s.gcOverheadPct()).isEqualTo(0.0);
        assertThat(s.collections()).isNull();
    }

    // --- Edge Cases ---

    @Test
    void handlesEmptyLog() throws IOException {
        GcSummary s = analyzeLog("");

        assertThat(s.analysisScope()).isEqualTo("empty");
        assertThat(s.warnings()).contains("GC log is empty or missing");
        assertThat(s.pause()).isNull();
    }

    @Test
    void handlesMissingGcLog() throws IOException {
        GcSummary s = analyzer.analyze(tempDir, "test-bench", "test-run");

        assertThat(s.analysisScope()).isEqualTo("empty");
        assertThat(s.warnings()).contains("GC log is empty or missing");
    }

    @Test
    void detectsPromotionFailures() throws IOException {
        GcSummary s = analyzeFixture("promotion-failure-sample.log");

        assertThat(s.heap().promotionFailures()).isEqualTo(2);
        assertThat(s.warnings()).anyMatch(w -> w.contains("Promotion failures"));
        assertThat(s.pause().countFull()).isEqualTo(2);
    }

    @Test
    void analysisQualityReflectsParseRate() throws IOException {
        GcSummary s = analyzeFixture("g1-sample.log");

        assertThat(s.analysisQuality()).isNotNull();
        assertThat(s.analysisQuality().parseCoveragePct()).isGreaterThan(0.0);
        assertThat(s.analysisQuality().parseCoveragePct()).isLessThanOrEqualTo(100.0);
        assertThat(s.analysisQuality().phaseAttribution()).isEqualTo("full");
    }

    @Test
    void writesJsonFile() throws IOException {
        analyzeFixture("g1-sample.log");

        Path jsonFile = tempDir.resolve("gc-summary.json");
        assertThat(jsonFile).exists();

        String json = Files.readString(jsonFile);
        assertThat(json)
                .contains("\"benchmarkId\" : \"test-bench\"")
                .contains("\"gcAlgorithm\" : \"G1\"");
    }

    // --- Percentile Calculation ---

    @Test
    void computesPercentilesCorrectly() {
        var sorted = java.util.List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0);

        Assertions.assertThat(PercentileCalculator.percentile(sorted, 0.50)).isEqualTo(5.5);
        assertThat(PercentileCalculator.percentile(sorted, 0.95)).isCloseTo(9.55, within(0.01));
        assertThat(PercentileCalculator.percentile(sorted, 0.99)).isCloseTo(9.91, within(0.01));
    }

    @Test
    void percentileSingleElement() {
        assertThat(PercentileCalculator.percentile(java.util.List.of(42.0), 0.99)).isEqualTo(42.0);
    }

    // --- Fork markers ---

    @Test
    void skipsForkMarkerLines() throws IOException {
        String log = """
                # === Fork: gc-12345.log ===
                [0ms] Using G1
                [100ms] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->8M(256M) 5.000ms

                # === Fork: gc-12346.log ===
                [200ms] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 30M->10M(256M) 3.000ms
                """;
        GcSummary s = analyzeLog(log);

        assertThat(s.pause().countTotal()).isEqualTo(2);
        assertThat(s.gcAlgorithm()).isEqualTo("G1");
    }

    @Test
    void sumsMultipleForkDurationsCorrectly() throws IOException {
        String log = """
                # === Fork: gc-1.log ===
                [2ms][info][gc] Using G1
                [1000ms][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->8M(256M) 5.000ms

                # === Fork: gc-2.log ===
                [2ms][info][gc] Using G1
                [1500ms][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 30M->10M(256M) 3.000ms
                """;
        GcSummary s = analyzeLog(log);

        // runDurationMs should be sum of both forks: 1000ms + 1500ms = 2500ms
        assertThat(s.runDurationMs()).isEqualTo(2500L);

        // Total pause: 5.0 + 3.0 = 8.0ms
        assertThat(s.pause().totalMs()).isEqualTo(8.0);

        // GC overhead: 8.0ms / 2500ms * 100 = 0.32%
        assertThat(s.gcOverheadPct()).isCloseTo(0.32, within(0.01));
    }
}
