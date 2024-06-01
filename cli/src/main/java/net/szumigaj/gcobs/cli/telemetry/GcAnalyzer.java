package net.szumigaj.gcobs.cli.telemetry;

import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.szumigaj.gcobs.cli.model.result.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Regex-based GC log parser. Reads merged gc.log (produced by BenchmarkExecutor.mergeGcLogs),
 * extracts pause events, safepoint metrics, heap stats, and writes gc-summary.json.
 */
@Slf4j
@Singleton
public class GcAnalyzer {

    // GC Pause events: handles both G1 format (two paren groups) and Parallel/Serial (one paren group)
    // G1:       Pause Young (Normal) (G1 Evacuation Pause) 24M->8M(256M) 5.432ms
    // Parallel: Pause Young (Allocation Failure) 20M->4M(128M) 8.200ms
    private static final Pattern GC_PAUSE = Pattern.compile(
            "Pause (Young|Mixed|Full).*?\\(([^)]+)\\)(?:.*?\\(([^)]+)\\))?.*?(\\d+)M->(\\d+)M\\((\\d+)M\\).*?([0-9.,]+)ms");

    // ZGC STW pauses: Pause Mark Start|Mark End|Relocate Start N.Nms
    private static final Pattern ZGC_PAUSE = Pattern.compile(
            "Pause (Mark Start|Mark End|Relocate Start).*?([0-9.]+)ms");

    // Safepoint TTSP in nanoseconds
    private static final Pattern SAFEPOINT_TTSP = Pattern.compile(
            "Reaching safepoint: (\\d+) ns");

    // Promotion failure indicator
    private static final Pattern PROMOTION_FAILURE = Pattern.compile("Promotion failed");

    // GC algorithm detection from startup log
    private static final Pattern GC_ALGORITHM = Pattern.compile(
            "Using (G1|ZGC|Parallel|Serial|Shenandoah|Epsilon)");

    // Uptime extraction from log line prefix [NNNms]
    private static final Pattern UPTIME = Pattern.compile("\\[(\\d+)ms\\]");

    // Fork separator line from mergeGcLogs
    private static final Pattern FORK_MARKER = Pattern.compile("^# === Fork:");

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

        List<String> lines = Files.readAllLines(gcLog);
        ParseResult result = parseLines(lines);
        populateSummary(summaryBuilder, result);

        GcSummary summary = summaryBuilder.build();
        JsonWriter.write(benchDir.resolve("gc-summary.json"), summary);
        return summary;
    }

    private ParseResult parseLines(List<String> lines) {
        ParseResult r = new ParseResult();
        int eventSeq = 0;
        long currentForkMaxUptime = 0;
        long totalDurationMs = 0;

        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }

            if (FORK_MARKER.matcher(line).find()) {
                totalDurationMs = handleForkMarker(currentForkMaxUptime, totalDurationMs);
                currentForkMaxUptime = 0;
                continue;
            }

            r.totalLines++;
            long uptime = extractUptime(line);
            currentForkMaxUptime = Math.max(currentForkMaxUptime, uptime);

            boolean matched = tryParseGcAlgorithm(line, r)
                    || tryParseSafepointTtsp(line, r)
                    || tryParsePromotionFailure(line, r);

            if (!matched) {
                int nextSeq = eventSeq + 1;
                matched = tryParseGcPause(line, r, nextSeq, uptime)
                        || tryParseZgcPause(line, r, nextSeq, uptime);
                if (matched) {
                    eventSeq = nextSeq;
                }
            }

            if (matched) {
                r.parsedLines++;
            }
        }

        r.maxUptimeMs = totalDurationMs + Math.max(0, currentForkMaxUptime);
        return r;
    }

    private long handleForkMarker(long currentForkMaxUptime, long totalDurationMs) {
        return currentForkMaxUptime > 0 ? totalDurationMs + currentForkMaxUptime : totalDurationMs;
    }

    private long extractUptime(String line) {
        Matcher matcher = UPTIME.matcher(line);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0;
    }

    private boolean tryParseGcAlgorithm(String line, ParseResult r) {
        Matcher matcher = GC_ALGORITHM.matcher(line);
        if (matcher.find()) {
            r.gcAlgorithm = matcher.group(1);
            return true;
        }
        return false;
    }

    private boolean tryParseGcPause(String line, ParseResult r, int eventSeq, long uptime) {
        Matcher matcher = GC_PAUSE.matcher(line);
        if (!matcher.find()) {
            return false;
        }

        String type = matcher.group(1);
        String cause = matcher.group(3) != null ? matcher.group(3) : matcher.group(2);
        int beforeMb = Integer.parseInt(matcher.group(4));
        int afterMb = Integer.parseInt(matcher.group(5));
        int capacityMb = Integer.parseInt(matcher.group(6));
        double durationMs = Double.parseDouble(matcher.group(7).replace(",", "."));

        r.events.add(new CollectionEvent(eventSeq, type, cause, beforeMb, afterMb, capacityMb, durationMs, uptime));
        r.pauseDurations.add(durationMs);
        r.reclaimedTotalMb += Math.max(0, beforeMb - afterMb);
        r.peakUsedMb = Math.max(r.peakUsedMb, beforeMb);

        switch (type) {
            case "Young" -> r.minorCount++;
            case "Mixed" -> r.mixedCount++;
            case "Full" -> r.fullCount++;
        }
        return true;
    }

    private boolean tryParseZgcPause(String line, ParseResult r, int eventSeq, long uptime) {
        Matcher matcher = ZGC_PAUSE.matcher(line);
        if (!matcher.find()) {
            return false;
        }

        double durationMs = Double.parseDouble(matcher.group(2));
        String phase = matcher.group(1);

        r.events.add(new CollectionEvent(eventSeq, "STW-Minor", phase, 0, 0, 0, durationMs, uptime));
        r.pauseDurations.add(durationMs);
        r.minorCount++;
        return true;
    }

    private boolean tryParseSafepointTtsp(String line, ParseResult r) {
        Matcher matcher = SAFEPOINT_TTSP.matcher(line);
        if (matcher.find()) {
            r.safepointTtspNs.add(Long.parseLong(matcher.group(1)));
            return true;
        }
        return false;
    }

    private boolean tryParsePromotionFailure(String line, ParseResult r) {
        if (PROMOTION_FAILURE.matcher(line).find()) {
            r.promotionFailures++;
            return true;
        }
        return false;
    }

    private void populateSummary(GcSummary.GcSummaryBuilder summaryBuilder, ParseResult r) {
        long runDurationMs = r.maxUptimeMs;
        summaryBuilder.gcAlgorithm(r.gcAlgorithm)
                .runDurationMs(runDurationMs)
                .analysisScope(r.totalLines == 0 ? "empty" : "full");

        AnalysisQuality.AnalysisQualityBuilder qualityBuilder = AnalysisQuality.builder()
                .phaseAttribution("full")
                .dataCompleteness(r.parsedLines > 0 ? "complete" : "partial");
        if (r.totalLines > 0) {
            double parseCoveragePct = (double) r.parsedLines / r.totalLines * 100.0;
            qualityBuilder.parseCoveragePct(parseCoveragePct);
            qualityBuilder.skippedLinesPct(100.0 - parseCoveragePct);
        }

        summaryBuilder.analysisQuality(qualityBuilder.build()) ;

        // Pause stats
        double pauseTotalMs = 0.0;
        PauseStats.PauseStatsBuilder pauseStatsBuilder = PauseStats.builder()
                .countTotal(r.pauseDurations.size())
                .countMinor(r.minorCount)
                .countMixed(r.mixedCount)
                .countFull(r.fullCount);

        if (!r.pauseDurations.isEmpty()) {
            List<Double> sorted = new ArrayList<>(r.pauseDurations);
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
        } else if ("Epsilon".equals(r.gcAlgorithm)) {
            summaryBuilder.gcOverheadPct(0.0);
        }

        // Heap stats
        HeapStats.HeapStatsBuilder heapStatsBuilder = HeapStats.builder();
        int reclaimedTotalMb = r.reclaimedTotalMb;
        heapStatsBuilder.peakUsedMb(r.peakUsedMb > 0 ? r.peakUsedMb : null)
                .reclaimedTotalMb(reclaimedTotalMb)
                .promotionFailures(r.promotionFailures);
        if (runDurationMs > 0) {
            heapStatsBuilder.allocationRateMbPerSec(reclaimedTotalMb / (runDurationMs / 1000.0));
        }
        summaryBuilder.heap(heapStatsBuilder.build());

        // Safepoint stats
        if (!r.safepointTtspNs.isEmpty()) {
            double totalNs = getSafepointTotalNs(r);
            double maxNs = getSafepointMaxNs(r);
            SafepointStats.SafepointStatsBuilder safepointStats = SafepointStats.builder()
                    .countTotal(r.safepointTtspNs.size())
                    .ttspMeanMs((totalNs / r.safepointTtspNs.size()) / 1_000_000.0)
                    .ttspMaxMs(maxNs / 1_000_000.0)
                    .timeTotalMs(totalNs / 1_000_000.0);
            summaryBuilder.safepoint(safepointStats.build());
        }

        // Collections array
        if (!r.events.isEmpty()) {
            summaryBuilder.collections(r.events);
        }

        if (!r.events.isEmpty()) {
            summaryBuilder.causeBreakdown(r.events.stream()
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
        if (r.gcAlgorithm == null) {
            warnings.add("Could not detect GC algorithm from log");
        }
        if (r.promotionFailures > 0) {
            warnings.add("Promotion failures detected (" + r.promotionFailures + "); Full GC escalation likely");
        }
        if (!warnings.isEmpty()) {
            summaryBuilder.warnings(warnings);
        }
    }

    private double getSafepointMaxNs(ParseResult r) {
        return r.safepointTtspNs.stream().mapToDouble(Long::doubleValue).max().orElse(0);
    }

    private double getSafepointTotalNs(ParseResult r) {
        return r.safepointTtspNs.stream().mapToDouble(Long::doubleValue).sum();
    }

    private static class ParseResult {
        String gcAlgorithm;
        List<CollectionEvent> events = new ArrayList<>();
        List<Double> pauseDurations = new ArrayList<>();
        List<Long> safepointTtspNs = new ArrayList<>();
        int promotionFailures;
        long maxUptimeMs;
        int peakUsedMb;
        int reclaimedTotalMb;
        int totalLines;
        int parsedLines;
        int minorCount;
        int mixedCount;
        int fullCount;
    }

}