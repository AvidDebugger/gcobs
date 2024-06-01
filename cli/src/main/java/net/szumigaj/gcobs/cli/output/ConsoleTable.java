package net.szumigaj.gcobs.cli.output;

import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.szumigaj.gcobs.cli.artifact.JmhScore;
import net.szumigaj.gcobs.cli.compare.CompareResult;
import net.szumigaj.gcobs.cli.executor.BenchmarkResult;
import net.szumigaj.gcobs.cli.model.result.GcSummary;
import net.szumigaj.gcobs.cli.model.result.HeapStats;
import net.szumigaj.gcobs.cli.model.result.PauseStats;
import net.szumigaj.gcobs.cli.model.result.SafepointStats;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static net.szumigaj.gcobs.cli.threshold.ThresholdResult.ThresholdStatus.FAIL;

@Slf4j
@Singleton
public class ConsoleTable {

    private static final int WIDTH = 62;
    private static final String PREFIX = "[gcobs] ";

    public void printGcTable(String benchmarkId, GcSummary gcSummary, JmhScore jmhScore) {
        printTableBorder(benchmarkId, true);

        if (gcSummary != null) {
            row("GC Algorithm", orNA(gcSummary.gcAlgorithm()));
            row("STW Overhead", formatGcOverhead(gcSummary.gcOverheadPct()));
            formatPauseStatistics(gcSummary.pause());
            formatHeapStatistics(gcSummary.heap());
            formatSafepointStatistics(gcSummary.safepoint());
        } else {
            row("Status", "No GC telemetry available");
        }

        formatJmhScore(jmhScore);
        printTableBorder(benchmarkId, false);
    }

    public void printDeltaTable(CompareResult result) {
        String header = "Comparison: " + result.pairId();
        String top = "┌─ " + header + " " + "─".repeat(Math.max(0, WIDTH - header.length() - 4)) + "┐";
        String bottom = "└" + "─".repeat(WIDTH - 2) + "┘";

        log.info(PREFIX + top);
        log.info("{}│ Verdict: {}│", PREFIX, result.verdict());
        log.info("{}│{}│", PREFIX, " ".repeat(WIDTH - 2));
        log.info("{}│ {} {} {} {} {}│",
                PREFIX, "Metric", "Base", "Candidate", "Delta", "Status");
        log.info("{}│ {} {} {} {} {}│",
                PREFIX, "────────────────", "─────────", "───────────", "─────────", "───────────");

        if (result.metrics() != null) {
            for (var m : result.metrics()) {
                String baseStr = fmtMetricVal(m.baseValue());
                String candStr = fmtMetricVal(m.candidateValue());
                String deltaStr = m.deltaPct() != null
                        ? String.format("%+.1f%%", m.deltaPct()) : "N/A";
                log.info("{}│ {} {} {} {} {}│",
                        PREFIX, truncate(m.name(), 16), baseStr, candStr, deltaStr, m.status());
            }
        }

        log.info(PREFIX + bottom);
    }

    private void printTableBorder(String benchmarkId, boolean isTop) {
        if (isTop) {
            String header = "GC Summary: " + benchmarkId;
            String top = "┌─ " + header + " " + "─".repeat(Math.max(0, WIDTH - header.length() - 4)) + "┐";
            log.info(PREFIX + top);
        } else {
            String bottom = "└" + "─".repeat(WIDTH - 2) + "┘";
            log.info(PREFIX + bottom);
        }
    }

    private String formatGcOverhead(Double gcOverheadPct) {
        return gcOverheadPct != null ? String.format("%.2f%%", gcOverheadPct) : "N/A";
    }

    private void formatPauseStatistics(PauseStats pause) {
        if (pause != null) {
            row("Collections", String.format("minor=%d  mixed=%d  full=%d",
                    pause.countMinor(), pause.countMixed(), pause.countFull()));
            row("Pause (ms)", String.format("min=%s  avg=%s  P95=%s  P99=%s  max=%s",
                    fmtMs(pause.minMs()), fmtMs(pause.meanMs()),
                    fmtMs(pause.p95Ms()), fmtMs(pause.p99Ms()),
                    fmtMs(pause.maxMs())));
        }
    }

    private void formatHeapStatistics(HeapStats heap) {
        if (heap != null) {
            row("Allocation Rate", heap.allocationRateMbPerSec() != null
                    ? String.format("%.1f MB/s", heap.allocationRateMbPerSec()) : "N/A");
            row("Peak Heap Used", heap.peakUsedMb() != null
                    ? heap.peakUsedMb() + " MB" : "N/A");
        }
    }

    private void formatSafepointStatistics(SafepointStats safepoint) {
        if (safepoint != null) {
            row("Safepoints", String.format("%d (TTSP max=%sms)",
                    safepoint.countTotal(), fmtMs(safepoint.ttspMaxMs())));
        }
    }

    private void formatJmhScore(JmhScore jmhScore) {
        if (jmhScore != null && jmhScore.score() != null) {
            String scoreStr = String.format("%.4f", jmhScore.score());
            if (jmhScore.scoreError() != null) {
                scoreStr += String.format(" ± %.4f", jmhScore.scoreError());
            }
            if (jmhScore.scoreUnit() != null) {
                scoreStr += " " + jmhScore.scoreUnit();
            }
            row("JMH Score", scoreStr);
        }
    }

    /**
     * Prints a run summary footer after all benchmarks complete.
     */
    public void printRunSummary(String runId, List<BenchmarkResult> results,
                                        Duration totalDuration, Path runDir) {
        String bar = "═".repeat(WIDTH);
        long succeeded = results.stream().filter(BenchmarkResult::isSuccess).count();

        long thresholdBreaches = results.stream()
                .filter(r -> r.thresholdResult() != null && FAIL.equals(r.thresholdResult().status()))
                .count();
        if (thresholdBreaches > 0) {
            log.info("{}  Thresholds:   {} breach(es)", PREFIX, thresholdBreaches);
        }

        log.info(PREFIX + bar);
        log.info("{}  Run Complete: {}", PREFIX, runId);
        log.info("{}  Benchmarks:   {}/{} succeeded", PREFIX, succeeded, results.size());
        log.info("{}  Duration:     {}s", PREFIX, totalDuration.getSeconds());
        log.info("{}  Artifacts:    {}/", PREFIX, runDir);
        log.info(PREFIX + bar);

        if (succeeded < results.size()) {
            log.info(PREFIX + "  Failed benchmarks:");
            results.stream()
                    .filter(r -> !r.isSuccess())
                    .forEach(r -> log.info("{}    - {} (exit {})",
                            PREFIX, r.benchmarkId(), r.exitCode()));
        }
    }

    private void row(String label, String value) {
        log.info("{}│ {} {}", PREFIX, label + ":", value);
    }

    private String fmtMs(Double val) {
        return val != null ? String.format("%.1f", val) : "N/A";
    }

    private String orNA(String val) {
        return val != null ? val : "N/A";
    }

    private String fmtMetricVal(Double val) {
        if (val == null) return "N/A";
        if (val == Math.floor(val) && val < 1_000_000) {
            return String.format("%.0f", val);
        }
        return String.format("%.4g", val);
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "...";
    }
}
