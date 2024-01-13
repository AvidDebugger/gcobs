package net.szumigaj.gcobs.cli.threshold;

import net.szumigaj.gcobs.cli.model.*;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static net.szumigaj.gcobs.cli.threshold.ThresholdResult.ThresholdStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class ThresholdValidatorTest {

    // --- Null / empty thresholds ---

    @Test
    void nullThresholdsReturnsNull() {
        ThresholdResult result = ThresholdValidator.evaluate(
                null, null, null, null, null, "invariant", "fail");
        assertThat(result).isNull();
    }

    @Test
    void allFieldsNullReturnsNull() {
        ThresholdResult result = ThresholdValidator.evaluate(
                ThresholdsConfig.builder().build(), null, null, null, null, "invariant", "fail");
        assertThat(result).isNull();
    }

    // --- Single threshold pass ---

    @Test
    void singleThresholdPasses() {
        ThresholdsConfig t = ThresholdsConfig.builder()
                .gcOverheadMaxPct(5.0)
                .build();

        GcSummary gc = GcSummary.builder()
                .gcOverheadPct(3.2)
                .build();

        ThresholdResult result = ThresholdValidator.evaluate(
                t, gc, null, null, null, "invariant", "fail");

        assertThat(result.status().name()).isEqualTo(PASS.name());
        assertThat(result.passing()).hasSize(1);
        assertThat(result.passing().get(0).field()).isEqualTo("gcOverheadMaxPct");
        assertThat(result.passing().get(0).actual()).isEqualTo(3.2);
        assertThat(result.breaches()).isNull();
        assertThat(result.skipped()).isNull();
    }

    // --- Single threshold breach ---

    @Test
    void singleThresholdBreached() {
        ThresholdsConfig t = ThresholdsConfig.builder()
                .gcPauseP99MaxMs(50.0)
                .build();

        GcSummary gc = GcSummary.builder()
                .pause(PauseStats.builder()
                        .p99Ms(84.2)
                        .build())
                .build();

        ThresholdResult result = ThresholdValidator.evaluate(
                t, gc, null, null, null, "invariant", "fail");

        assertThat(result.status().name()).isEqualTo(FAIL.name());
        assertThat(result.breaches()).hasSize(1);
        assertThat(result.breaches().get(0).field()).isEqualTo("gcPauseP99MaxMs");
        assertThat(result.breaches().get(0).actual()).isEqualTo(84.2);
        assertThat(result.breaches().get(0).threshold()).isEqualTo(50.0);
        assertThat(result.breaches().get(0).message()).contains("84.2").contains("50.0");
    }

    @Test
    void exactlyAtThresholdPasses() {
        ThresholdsConfig t = ThresholdsConfig.builder()
                .gcOverheadMaxPct(5.0)
                .build();

        GcSummary gc = GcSummary.builder()
                .gcOverheadPct(5.0)
                .build();

        ThresholdResult result = ThresholdValidator.evaluate(
                t, gc, null, null, null, "invariant", "fail");

        assertThat(result.status().name()).isEqualTo(PASS.name());
        assertThat(result.passing()).hasSize(1);
    }

    // --- Missing metric handling ---

    @Test
    void missingMetricInvariantFailMode() {
        ThresholdsConfig t = ThresholdsConfig.builder()
                .gcOverheadMaxPct(5.0)
                .build();

        // gcSummary is null, metric unavailable
        ThresholdResult result = ThresholdValidator.evaluate(
                t, null, null, null, null, "invariant", "fail");

        assertThat(result.status().name()).isEqualTo(FAIL.name());
        assertThat(result.breaches()).hasSize(1);
        assertThat(result.breaches().get(0).message()).contains("unavailable");
    }

    @Test
    void missingMetricInvariantSkipMode() {
        ThresholdsConfig t = ThresholdsConfig.builder()
                .gcOverheadMaxPct(5.0)
                .build();

        ThresholdResult result = ThresholdValidator.evaluate(
                t, null, null, null, null, "invariant", "skip");

        assertThat(result.status().name()).isEqualTo(SKIPPED.name());
        assertThat(result.skipped()).hasSize(1);
        assertThat(result.skipped().get(0).reason()).contains("onMissingMetric=skip");
        assertThat(result.breaches()).isNull();
    }

    @Test
    void missingMetricExploreMode() {
        ThresholdsConfig t = ThresholdsConfig.builder()
                .gcOverheadMaxPct(5.0)
                .build();

        ThresholdResult result = ThresholdValidator.evaluate(
                t, null, null, null, null, "explore", "fail");

        assertThat(result.status().name()).isEqualTo(SKIPPED.name());
        assertThat(result.skipped()).hasSize(1);
        assertThat(result.skipped().get(0).reason()).contains("explore mode");
    }

    // --- Multiple thresholds, mixed results ---

    @Test
    void mixedPassAndFail() {
        ThresholdsConfig t = ThresholdsConfig.builder()
                .gcOverheadMaxPct(5.0)
                .gcPauseP99MaxMs(50.0)
                .gcFullCountMax(0)
                .build();

        GcSummary gc = GcSummary.builder()
                .gcOverheadPct(2.3)
                .pause(PauseStats.builder()
                        .p99Ms(84.2)
                        .countFull(0)
                        .build())
                .build();

        ThresholdResult result = ThresholdValidator.evaluate(
                t, gc, null, null, null, "invariant", "fail");

        assertThat(result.status().name()).isEqualTo(FAIL.name());
        assertThat(result.breaches()).hasSize(1);
        assertThat(result.breaches().get(0).field()).isEqualTo("gcPauseP99MaxMs");
        assertThat(result.passing()).hasSize(2);
    }

    // --- Pause stat thresholds ---

    @Test
    void pauseP95Threshold() {
        ThresholdsConfig t = ThresholdsConfig.builder()
                .gcPauseP95MaxMs(100.0)
                .build();

        GcSummary gc = GcSummary.builder()
                .pause(PauseStats.builder()
                        .p95Ms(120.0)
                        .build())
                .build();

        ThresholdResult result = ThresholdValidator.evaluate(
                t, gc, null, null, null, "invariant", "fail");

        assertThat(result.status().name()).isEqualTo(FAIL.name());
        assertThat(result.breaches().get(0).field()).isEqualTo("gcPauseP95MaxMs");
    }

    @Test
    void pauseMaxThreshold() {
        ThresholdsConfig t = ThresholdsConfig.builder()
                .gcPauseMaxMs(200.0)
                .build();

        GcSummary gc = GcSummary.builder()
                .pause(PauseStats.builder()
                        .maxMs(150.0)
                        .build())
                .build();

        ThresholdResult result = ThresholdValidator.evaluate(
                t, gc, null, null, null, "invariant", "fail");

        assertThat(result.status().name()).isEqualTo(PASS.name());
    }

    // --- gcFullCountMax ---

    @Test
    void fullGcCountBreached() {
        ThresholdsConfig t = ThresholdsConfig.builder()
                .gcFullCountMax(0)
                .build();

        GcSummary gc = GcSummary.builder()
                .pause(PauseStats.builder()
                        .countFull(3)
                        .build())
                .build();

        ThresholdResult result = ThresholdValidator.evaluate(
                t, gc, null, null, null, "invariant", "fail");

        assertThat(result.status().name()).isEqualTo(FAIL.name());
        assertThat(result.breaches().get(0).actual()).isEqualTo(3.0);
    }

    // --- gcAllocationStallsMax ---

    @Test
    void allocationStallsFromJfr() {
        ThresholdsConfig t = ThresholdsConfig.builder()
                .gcAllocationStallsMax(0)
                .build();

        JfrSummary jfr = JfrSummary.builder()
                .allocationStalls(2)
                .build();

        ThresholdResult result = ThresholdValidator.evaluate(
                t, null, jfr, null, null, "invariant", "fail");

        assertThat(result.status().name()).isEqualTo(FAIL.name());
        assertThat(result.breaches().get(0).field()).isEqualTo("gcAllocationStallsMax");
    }

    @Test
    void allocationStallsMissingWhenNoJfr() {
        ThresholdsConfig t = ThresholdsConfig.builder()
                .gcAllocationStallsMax(5)
                .build();

        ThresholdResult result = ThresholdValidator.evaluate(
                t, null, null, null, null, "invariant", "fail");

        assertThat(result.status().name()).isEqualTo(FAIL.name());
        assertThat(result.breaches().get(0).message()).contains("unavailable");
    }

    // --- heapPeakUsedMaxMb ---

    @Test
    void heapPeakUsedPasses() {
        ThresholdsConfig t = ThresholdsConfig.builder()
                .heapPeakUsedMaxMb(512)
                .build();

        GcSummary gc = GcSummary.builder()
                .heap(HeapStats.builder()
                        .peakUsedMb(400)
                        .build())
                .build();

        ThresholdResult result = ThresholdValidator.evaluate(
                t, gc, null, null, null, "invariant", "fail");

        assertThat(result.status().name()).isEqualTo(PASS.name());
    }

    @Test
    void heapPeakUsedBreached() {
        ThresholdsConfig t = ThresholdsConfig.builder()
                .heapPeakUsedMaxMb(256)
                .build();

        GcSummary gc = GcSummary.builder()
                .heap(HeapStats.builder()
                        .peakUsedMb(400)
                        .build())
                .build();

        ThresholdResult result = ThresholdValidator.evaluate(
                t, gc, null, null, null, "invariant", "fail");

        assertThat(result.status().name()).isEqualTo(FAIL.name());
    }

    // --- gcCpuMaxPct ---

    @Test
    void gcCpuPctPasses() {
        ThresholdsConfig t = ThresholdsConfig.builder()
                .gcCpuMaxPct(10.0)
                .build();

        GcSummary gc = GcSummary.builder()
                .gcCpuPct(5.0)
                .build();

        ThresholdResult result = ThresholdValidator.evaluate(
                t, gc, null, null, null, "invariant", "fail");

        assertThat(result.status().name()).isEqualTo(PASS.name());
    }

    // --- gcSystemGcCountMax ---

    @Test
    void systemGcCountZeroWhenNoCauseBreakdown() {
        ThresholdsConfig t = ThresholdsConfig.builder()
                .gcSystemGcCountMax(0)
                .build();

        GcSummary gc = GcSummary.builder().build();
        // causeBreakdown is null, means no System.gc() observed -> actual=0

        ThresholdResult result = ThresholdValidator.evaluate(
                t, gc, null, null, null, "invariant", "fail");

        assertThat(result.status().name()).isEqualTo(PASS.name());
        assertThat(result.passing().get(0).actual()).isEqualTo(0.0);
    }

    @Test
    void systemGcCountZeroWhenKeyAbsent() {
        ThresholdsConfig t = ThresholdsConfig.builder()
                .gcSystemGcCountMax(0)
                .build();

        GcSummary gc = GcSummary.builder()
                .causeBreakdown(Map.of("G1 Evacuation Pause", new CauseEntry(5, 100.0)))
                .build();

        ThresholdResult result = ThresholdValidator.evaluate(
                t, gc, null, null, null, "invariant", "fail");

        assertThat(result.status().name()).isEqualTo(PASS.name());
    }

    @Test
    void systemGcCountBreached() {
        ThresholdsConfig t = ThresholdsConfig.builder()
                .gcSystemGcCountMax(0)
                .build();

        GcSummary gc = GcSummary.builder()
                .causeBreakdown(Map.of("System.gc()", new CauseEntry(3, 50.0)))
                .build();

        ThresholdResult result = ThresholdValidator.evaluate(
                t, gc, null, null, null, "invariant", "fail");

        assertThat(result.status().name()).isEqualTo(FAIL.name());
        assertThat(result.breaches().get(0).actual()).isEqualTo(3.0);
    }

    @Test
    void systemGcCountMissingWhenGcSummaryNull() {
        // gcSystemGcCountMax with null gcSummary, still count as 0,
        // but gcSummary itself is null, so causeBreakdown is null
        ThresholdsConfig t = ThresholdsConfig.builder()
                .gcSystemGcCountMax(0)
                .build();

        // When gcSummary is null, causeBreakdown check still yields actual=0
        ThresholdResult result = ThresholdValidator.evaluate(
                t, null, null, null, null, "invariant", "fail");

        assertThat(result.status().name()).isEqualTo(PASS.name());
        assertThat(result.passing().get(0).actual()).isEqualTo(0.0);
    }

    // --- metaspaceUsedMaxMb (always unavailable in JfrSummary v1) ---

    @Test
    void metaspaceAlwaysUnavailableInV1() {
        ThresholdsConfig t = ThresholdsConfig.builder()
                .metaspaceUsedMaxMb(256)
                .build();

        JfrSummary jfr = JfrSummary.builder().build();

        ThresholdResult result = ThresholdValidator.evaluate(
                t, null, jfr, null, null, "invariant", "fail");

        assertThat(result.status().name()).isEqualTo(FAIL.name());
        assertThat(result.breaches().get(0).field()).isEqualTo("metaspaceUsedMaxMb");
        assertThat(result.breaches().get(0).message()).contains("unavailable");
    }

    @Test
    void metaspaceSkippedInExploreMode() {
        ThresholdsConfig t = ThresholdsConfig.builder()
                .metaspaceUsedMaxMb(256)
                .build();

        ThresholdResult result = ThresholdValidator.evaluate(
                t, null, null, null, null, "explore", "fail");

        assertThat(result.status().name()).isEqualTo(SKIPPED.name());
        assertThat(result.skipped().get(0).field()).isEqualTo("metaspaceUsedMaxMb");
    }

    // --- jmhScoreRegressionMaxPct ---

    @Test
    void jmhRegressionWithNoBaselineIsMissingMetric() {
        ThresholdsConfig t = ThresholdsConfig.builder()
                .jmhScoreRegressionMaxPct(10.0)
                .build();

        ThresholdResult result = ThresholdValidator.evaluate(
                t, null, null, null, 0.018, "invariant", "fail");

        assertThat(result.status().name()).isEqualTo(FAIL.name());
        assertThat(result.breaches().get(0).field()).isEqualTo("jmhScoreRegressionMaxPct");
        assertThat(result.breaches().get(0).message()).contains("unavailable");
    }

    @Test
    void jmhRegressionWithNoBaselineSkippedInExplore() {
        ThresholdsConfig t = ThresholdsConfig.builder()
                .jmhScoreRegressionMaxPct(10.0)
                .build();

        ThresholdResult result = ThresholdValidator.evaluate(
                t, null, null, null, 0.018, "explore", "fail");

        assertThat(result.status().name()).isEqualTo(SKIPPED.name());
    }

    @Test
    void jmhRegressionBreached() {
        ThresholdsConfig t = ThresholdsConfig.builder()
                .jmhScoreRegressionMaxPct(10.0)
                .build();

        // For lower-is-better (AverageTime): baseline=0.010, current=0.015 -> 50% regression
        ThresholdResult result = ThresholdValidator.evaluate(
                t, null, null, 0.010, 0.015, "invariant", "fail");

        assertThat(result.status().name()).isEqualTo(FAIL.name());
        assertThat(result.breaches().get(0).field()).isEqualTo("jmhScoreRegressionMaxPct");
        assertThat(result.breaches().get(0).message()).contains("50.0%");
    }

    @Test
    void jmhRegressionPasses() {
        ThresholdsConfig t = ThresholdsConfig.builder()
                .jmhScoreRegressionMaxPct(20.0)
                .build();

        // baseline=0.010, current=0.011 -> 10% regression, within 20% threshold
        ThresholdResult result = ThresholdValidator.evaluate(
                t, null, null, 0.010, 0.011, "invariant", "fail");

        assertThat(result.status().name()).isEqualTo(PASS.name());
        assertThat(result.passing().get(0).field()).isEqualTo("jmhScoreRegressionMaxPct");
    }

    @Test
    void jmhImprovementPasses() {
        ThresholdsConfig t = ThresholdsConfig.builder()
                .jmhScoreRegressionMaxPct(10.0)
                .build();

        // baseline=0.020, current=0.010 -> -50% (improvement)
        ThresholdResult result = ThresholdValidator.evaluate(
                t, null, null, 0.020, 0.010, "invariant", "fail");

        assertThat(result.status().name()).isEqualTo(PASS.name());
    }

    @Test
    void jmhBaselineZeroSkipped() {
        ThresholdsConfig t = ThresholdsConfig.builder()
                .jmhScoreRegressionMaxPct(10.0)
                .build();

        ThresholdResult result = ThresholdValidator.evaluate(
                t, null, null, 0.0, 0.010, "invariant", "fail");

        assertThat(result.status().name()).isEqualTo(SKIPPED.name());
        assertThat(result.skipped().get(0).reason()).contains("zero");
    }

    // --- Full scenario ---

    @Test
    void fullScenarioWithMultipleThresholds() {
        ThresholdsConfig t = ThresholdsConfig.builder()
                .gcPauseP99MaxMs(150.0)
                .gcFullCountMax(0)
                .gcOverheadMaxPct(15.0)
                .gcAllocationStallsMax(0)
                .build();

        GcSummary gc = GcSummary.builder()
                .gcOverheadPct(4.5)
                .pause(PauseStats.builder()
                        .p99Ms(42.0)
                        .countFull(0)
                        .build())
                .build();

        JfrSummary jfr = JfrSummary.builder()
                .allocationStalls(0)
                .build();

        ThresholdResult result = ThresholdValidator.evaluate(
                t, gc, jfr, null, null, "invariant", "fail");

        assertThat(result.status().name()).isEqualTo(PASS.name());
        assertThat(result.passing()).hasSize(4);
        assertThat(result.breaches()).isNull();
        assertThat(result.skipped()).isNull();
    }
}
