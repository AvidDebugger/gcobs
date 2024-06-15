package net.szumigaj.gcobs.cli.telemetry.gc;

import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.szumigaj.gcobs.cli.model.result.*;
import net.szumigaj.gcobs.cli.telemetry.JsonWriter;
import net.szumigaj.gcobs.cli.telemetry.PercentileCalculator;
import net.szumigaj.gcobs.cli.telemetry.gc.parser.GcLogParserDispatcher;
import net.szumigaj.gcobs.cli.telemetry.gc.parser.ParserResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Regex-based GC log parser. Reads merged gc.log (produced by BenchmarkExecutor.mergeGcLogs),
 * extracts pause events, safepoint metrics, heap stats, and writes gc-summary.json.
 * Delegates line-by-line parsing to algorithm-specific strategies via GcLogParserDispatcher.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor
public class GcAnalyzer {

    private final GcLogParserDispatcher parserDispatcher;

    /**
     * Analyzes gc.log in benchDir and writes gc-summary.json.
     *
     * @return the populated GcSummary (also written to disk)
     */
    public GcSummary analyze(Path benchDir, String benchmarkId, String runId)
            throws IOException {
        Path gcLog = benchDir.resolve("gc.log");
        GcSummary.GcSummaryBuilder summaryBuilder = GcSummary.builder()
                .benchmarkId(benchmarkId)
                .runId(runId);

        if (!Files.exists(gcLog) || Files.size(gcLog) == 0) {
            summaryBuilder.analysisScope("empty")
                    .warnings(List.of("GC log is empty or missing"));

            GcSummary summary = summaryBuilder.build();
            JsonWriter.write(benchDir.resolve("gc-summary.json"), summary);
            return summary;
        }

        try (BufferedReader input = Files.newBufferedReader(gcLog)) {
            ParserResult result = parserDispatcher.parse(input);
            populateSummary(summaryBuilder, result);
        }

        GcSummary summary = summaryBuilder.build();
        JsonWriter.write(benchDir.resolve("gc-summary.json"), summary);
        return summary;
    }

    private void populateSummary(GcSummary.GcSummaryBuilder summaryBuilder, ParserResult parserResult) {
        long runDurationMs = parserResult.maxUptimeMs();
        summaryBuilder.gcAlgorithm(parserResult.gcAlgorithm())
                .runDurationMs(runDurationMs)
                .analysisScope(parserResult.totalLines() == 0 ? "empty" : "full");

        AnalysisQuality.AnalysisQualityBuilder qualityBuilder = AnalysisQuality.builder()
                .phaseAttribution("full")
                .dataCompleteness(parserResult.parsedLines() > 0 ? "complete" : "partial");
        if (parserResult.totalLines() > 0) {
            double parseCoveragePct = (double) parserResult.parsedLines() / parserResult.totalLines() * 100.0;
            qualityBuilder.parseCoveragePct(parseCoveragePct);
            qualityBuilder.skippedLinesPct(100.0 - parseCoveragePct);
        }

        summaryBuilder.analysisQuality(qualityBuilder.build());

        // Pause stats
        double pauseTotalMs = 0.0;
        PauseStats.PauseStatsBuilder pauseStatsBuilder = PauseStats.builder()
                .countTotal(parserResult.pauseDurations().size())
                .countMinor(parserResult.minorCount())
                .countMixed(parserResult.mixedCount())
                .countFull(parserResult.fullCount());

        if (!parserResult.pauseDurations().isEmpty()) {
            List<Double> sorted = new ArrayList<>(parserResult.pauseDurations());
            Collections.sort(sorted);
            pauseTotalMs = sorted.stream().mapToDouble(Double::doubleValue).sum();

            pauseStatsBuilder.totalMs(pauseTotalMs);
            pauseStatsBuilder.minMs(sorted.get(0));
            pauseStatsBuilder.maxMs(sorted.get(sorted.size() - 1));
            pauseStatsBuilder.meanMs(pauseTotalMs / sorted.size());
            pauseStatsBuilder.p50Ms(PercentileCalculator.percentile(sorted, 0.50));
            pauseStatsBuilder.p95Ms(PercentileCalculator.percentile(sorted, 0.95));
            pauseStatsBuilder.p99Ms(PercentileCalculator.percentile(sorted, 0.99));
        } else {
            pauseStatsBuilder.totalMs(0.0);
        }
        pauseStatsBuilder.totalMs(pauseTotalMs);
        summaryBuilder.pause(pauseStatsBuilder.build());

        // GC overhead
        if (runDurationMs > 0) {
            summaryBuilder.gcOverheadPct(pauseTotalMs / runDurationMs * 100.0);
        } else if ("Epsilon".equals(parserResult.gcAlgorithm())) {
            summaryBuilder.gcOverheadPct(0.0);
        }

        // Heap stats
        HeapStats.HeapStatsBuilder heapStatsBuilder = HeapStats.builder();
        int reclaimedTotalMb = parserResult.reclaimedTotalMb();
        heapStatsBuilder.peakUsedMb(parserResult.peakUsedMb() > 0 ? parserResult.peakUsedMb() : null)
                .reclaimedTotalMb(reclaimedTotalMb)
                .promotionFailures(parserResult.promotionFailures());
        if (runDurationMs > 0) {
            heapStatsBuilder.allocationRateMbPerSec(reclaimedTotalMb / (runDurationMs / 1000.0));
        }
        summaryBuilder.heap(heapStatsBuilder.build());

        // Safepoint stats
        if (!parserResult.safepointTtspNs().isEmpty()) {
            double totalNs = getSafepointTotalNs(parserResult);
            double maxNs = getSafepointMaxNs(parserResult);
            SafepointStats.SafepointStatsBuilder safepointStats = SafepointStats.builder()
                    .countTotal(parserResult.safepointTtspNs().size())
                    .ttspMeanMs((totalNs / parserResult.safepointTtspNs().size()) / 1_000_000.0)
                    .ttspMaxMs(maxNs / 1_000_000.0)
                    .timeTotalMs(totalNs / 1_000_000.0);
            summaryBuilder.safepoint(safepointStats.build());
        }

        // Collections array
        if (!parserResult.events().isEmpty()) {
            summaryBuilder.collections(parserResult.events());
        }

        if (!parserResult.events().isEmpty()) {
            summaryBuilder.causeBreakdown(parserResult.events().stream()
                    .collect(Collectors.groupingBy(
                            CollectionEvent::cause,
                            LinkedHashMap::new,
                            Collectors.collectingAndThen(
                                    Collectors.toList(),
                                    events -> new CauseEntry(
                                            events.size(),
                                            events.stream().mapToDouble(CollectionEvent::durationMs).sum()
                                    )
                            ))));
        }

        List<String> warnings = new ArrayList<>();
        if (parserResult.gcAlgorithm() == null) {
            warnings.add("Could not detect GC algorithm from log");
        }
        if (parserResult.promotionFailures() > 0) {
            warnings.add("Promotion failures detected (" + parserResult.promotionFailures() + "), Full GC escalation likely");
        }
        if (!warnings.isEmpty()) {
            summaryBuilder.warnings(warnings);
        }
    }

    private double getSafepointMaxNs(ParserResult r) {
        return r.safepointTtspNs().stream().mapToDouble(Long::doubleValue).max().orElse(0);
    }

    private double getSafepointTotalNs(ParserResult r) {
        return r.safepointTtspNs().stream().mapToDouble(Long::doubleValue).sum();
    }
}
