package net.szumigaj.gcobs.cli.threshold;

import net.szumigaj.gcobs.cli.model.result.CauseEntry;
import net.szumigaj.gcobs.cli.model.result.GcSummary;
import net.szumigaj.gcobs.cli.model.result.JfrSummary;
import net.szumigaj.gcobs.cli.model.config.ThresholdsConfig;

import java.util.ArrayList;
import java.util.List;

import static net.szumigaj.gcobs.cli.threshold.ThresholdResult.ThresholdStatus.*;

/**
 * Evaluates declared threshold fields against actual GC/JFR/JMH metrics.
 * Returns null when no thresholds are declared (ThresholdsConfig is null or all fields null).
 */
public final class ThresholdValidator {

    private ThresholdValidator() {}

    /**
     * Evaluates all declared thresholds for a single benchmark.
     *
     * @param thresholds       threshold declarations from effective config; may be null
     * @param gcSummary        parsed gc-summary.json; may be null
     * @param jfrSummary       parsed jfr-summary.json; may be null
     * @param baselineJmhScore baseline JMH score for regression check; null if no baseline
     * @param currentJmhScore  current benchmark JMH score; may be null
     * @param profileMode      "invariant" or "explore"
     * @param onMissingMetric  "fail" or "skip" (from validation config)
     * @return ThresholdResult, or null if no thresholds declared
     */
    public static ThresholdResult evaluate(
            ThresholdsConfig thresholds,
            GcSummary gcSummary,
            JfrSummary jfrSummary,
            Double baselineJmhScore,
            Double currentJmhScore,
            String profileMode,
            String onMissingMetric) {

        if (thresholds == null || !hasAnyThreshold(thresholds)) {
            return null;
        }

        boolean isExplore = "explore".equals(profileMode);
        boolean skipOnMissing = "skip".equals(onMissingMetric);

        List<ThresholdResult.Breach> breaches = new ArrayList<>();
        List<ThresholdResult.PassingEntry> passing = new ArrayList<>();
        List<ThresholdResult.SkippedEntry> skipped = new ArrayList<>();

        // gcOverheadMaxPct
        if (thresholds.gcOverheadMaxPct() != null) {
            Double actual = gcSummary != null ? gcSummary.gcOverheadPct() : null;
            checkThreshold("gcOverheadMaxPct", thresholds.gcOverheadMaxPct(), actual,
                    "GC overhead %.2f%% exceeds threshold %.2f%%",
                    isExplore, skipOnMissing, breaches, passing, skipped);
        }

        // gcPauseP95MaxMs
        if (thresholds.gcPauseP95MaxMs() != null) {
            Double actual = (gcSummary != null && gcSummary.pause() != null) ? gcSummary.pause().p95Ms() : null;
            checkThreshold("gcPauseP95MaxMs", thresholds.gcPauseP95MaxMs(), actual,
                    "P95 GC pause %.1fms exceeds threshold %.1fms",
                    isExplore, skipOnMissing, breaches, passing, skipped);
        }

        // gcPauseP99MaxMs
        if (thresholds.gcPauseP99MaxMs() != null) {
            Double actual = (gcSummary != null && gcSummary.pause() != null) ? gcSummary.pause().p99Ms() : null;
            checkThreshold("gcPauseP99MaxMs", thresholds.gcPauseP99MaxMs(), actual,
                    "P99 GC pause %.1fms exceeds threshold %.1fms",
                    isExplore, skipOnMissing, breaches, passing, skipped);
        }

        // gcPauseMaxMs
        if (thresholds.gcPauseMaxMs() != null) {
            Double actual = (gcSummary != null && gcSummary.pause() != null) ? gcSummary.pause().maxMs() : null;
            checkThreshold("gcPauseMaxMs", thresholds.gcPauseMaxMs(), actual,
                    "Max GC pause %.1fms exceeds threshold %.1fms",
                    isExplore, skipOnMissing, breaches, passing, skipped);
        }

        // gcFullCountMax
        if (thresholds.gcFullCountMax() != null) {
            Double actual = (gcSummary != null && gcSummary.pause() != null)
                    ? (double) gcSummary.pause().countFull() : null;
            checkThreshold("gcFullCountMax", thresholds.gcFullCountMax(), actual,
                    "Full GC count %.0f exceeds threshold %.0f",
                    isExplore, skipOnMissing, breaches, passing, skipped);
        }

        // gcAllocationStallsMax
        if (thresholds.gcAllocationStallsMax() != null) {
            Double actual = jfrSummary != null ? (double) jfrSummary.allocationStalls() : null;
            checkThreshold("gcAllocationStallsMax", thresholds.gcAllocationStallsMax(), actual,
                    "Allocation stalls %.0f exceeds threshold %.0f",
                    isExplore, skipOnMissing, breaches, passing, skipped);
        }

        // heapPeakUsedMaxMb
        if (thresholds.heapPeakUsedMaxMb() != null) {
            Double actual = (gcSummary != null && gcSummary.heap() != null && gcSummary.heap().peakUsedMb() != null)
                    ? gcSummary.heap().peakUsedMb().doubleValue() : null;
            checkThreshold("heapPeakUsedMaxMb", thresholds.heapPeakUsedMaxMb(), actual,
                    "Peak heap used %.0fMB exceeds threshold %.0fMB",
                    isExplore, skipOnMissing, breaches, passing, skipped);
        }

        // jmhScoreRegressionMaxPct
        if (thresholds.jmhScoreRegressionMaxPct() != null) {
            evaluateJmhRegression(thresholds.jmhScoreRegressionMaxPct(),
                    baselineJmhScore, currentJmhScore,
                    isExplore, skipOnMissing, breaches, passing, skipped);
        }

        // gcCpuMaxPct
        if (thresholds.gcCpuMaxPct() != null) {
            Double actual = gcSummary != null ? gcSummary.gcCpuPct() : null;
            checkThreshold("gcCpuMaxPct", thresholds.gcCpuMaxPct(), actual,
                    "GC CPU %.2f%% exceeds threshold %.2f%%",
                    isExplore, skipOnMissing, breaches, passing, skipped);
        }

        // gcSystemGcCountMax absent cause means 0, not "missing"
        if (thresholds.gcSystemGcCountMax() != null) {
            int actual = 0;
            if (gcSummary != null && gcSummary.causeBreakdown() != null) {
                CauseEntry entry = gcSummary.causeBreakdown().get("System.gc()");
                if (entry != null) {
                    actual = entry.count();
                }
            }
            // System.gc() count of 0 when no causeBreakdown is intentional, not a missing metric
            checkThreshold("gcSystemGcCountMax", thresholds.gcSystemGcCountMax().doubleValue(),
                    (double) actual,
                    "System.gc() count %.0f exceeds threshold %.0f",
                    isExplore, skipOnMissing, breaches, passing, skipped);
        }

        if (thresholds.metaspaceUsedMaxMb() != null) {
            Double actual = (jfrSummary != null && jfrSummary.memoryPools() != null
                    && jfrSummary.memoryPools().metaspace() != null)
                    ? jfrSummary.memoryPools().metaspace().usedMaxMb() : null;
            checkThreshold("metaspaceUsedMaxMb", thresholds.metaspaceUsedMaxMb().doubleValue(), actual,
                    "Metaspace used %.0fMB exceeds threshold %.0fMB",
                    isExplore, skipOnMissing, breaches, passing, skipped);
        }

        // Determine status
        ThresholdResult.ThresholdResultBuilder resultBuilder = ThresholdResult.builder();
        if (!breaches.isEmpty()) {
            resultBuilder.status(FAIL);
        } else if (!passing.isEmpty()) {
            resultBuilder.status(PASS);
        } else {
            resultBuilder.status(SKIPPED);
        }

        resultBuilder.breaches(breaches.isEmpty() ? null : breaches);
        resultBuilder.passing(passing.isEmpty() ? null : passing);
        resultBuilder.skipped(skipped.isEmpty() ? null : skipped);

        return resultBuilder.build();
    }

    private static void checkThreshold(String field, double threshold, Double actual,
                                        String messageFormat,
                                        boolean isExplore, boolean skipOnMissing,
                                        List<ThresholdResult.Breach> breaches,
                                        List<ThresholdResult.PassingEntry> passing,
                                        List<ThresholdResult.SkippedEntry> skipped) {
        if (actual == null) {
            handleMissingMetric(field, threshold, isExplore, skipOnMissing, breaches, skipped);
            return;
        }

        if (actual > threshold) {
            String message = String.format(messageFormat, actual, threshold);
            breaches.add(new ThresholdResult.Breach(field, threshold, actual, message));
        } else {
            passing.add(new ThresholdResult.PassingEntry(field, threshold, actual));
        }
    }

    private static void handleMissingMetric(String field, double threshold,
                                             boolean isExplore, boolean skipOnMissing,
                                             List<ThresholdResult.Breach> breaches,
                                             List<ThresholdResult.SkippedEntry> skipped) {
        if (isExplore) {
            skipped.add(new ThresholdResult.SkippedEntry(field, "metric unavailable (explore mode)"));
        } else if (skipOnMissing) {
            skipped.add(new ThresholdResult.SkippedEntry(field, "metric unavailable (onMissingMetric=skip)"));
        } else {
            breaches.add(new ThresholdResult.Breach(field, threshold, 0,
                    field + ": metric unavailable"));
        }
    }

    private static void evaluateJmhRegression(double maxRegressionPct,
                                               Double baselineScore, Double currentScore,
                                               boolean isExplore, boolean skipOnMissing,
                                               List<ThresholdResult.Breach> breaches,
                                               List<ThresholdResult.PassingEntry> passing,
                                               List<ThresholdResult.SkippedEntry> skipped) {
        String field = "jmhScoreRegressionMaxPct";

        if (baselineScore == null || currentScore == null) {
            handleMissingMetric(field, maxRegressionPct, isExplore, skipOnMissing, breaches, skipped);
            return;
        }

        if (baselineScore == 0.0) {
            skipped.add(new ThresholdResult.SkippedEntry(field, "baseline score is zero"));
            return;
        }

        // For lower-is-better (AverageTime): higher current = regression
        // regressionPct = ((current - baseline) / baseline) * 100
        double regressionPct = ((currentScore - baselineScore) / baselineScore) * 100.0;

        if (regressionPct > maxRegressionPct) {
            String message = String.format(
                    "JMH score regression %.1f%% exceeds threshold %.1f%% (baseline=%.6f, current=%.6f)",
                    regressionPct, maxRegressionPct, baselineScore, currentScore);
            breaches.add(new ThresholdResult.Breach(field, maxRegressionPct, regressionPct, message));
        } else {
            passing.add(new ThresholdResult.PassingEntry(field, maxRegressionPct, regressionPct));
        }
    }

    private static boolean hasAnyThreshold(ThresholdsConfig t) {
        return t.gcOverheadMaxPct() != null
                || t.gcPauseP95MaxMs() != null
                || t.gcPauseP99MaxMs() != null
                || t.gcPauseMaxMs() != null
                || t.gcFullCountMax() != null
                || t.gcAllocationStallsMax() != null
                || t.heapPeakUsedMaxMb() != null
                || t.jmhScoreRegressionMaxPct() != null
                || t.gcCpuMaxPct() != null
                || t.gcSystemGcCountMax() != null
                || t.metaspaceUsedMaxMb() != null;
    }
}
