package net.szumigaj.gcobs.cli.telemetry.gc.parser;

import net.szumigaj.gcobs.cli.model.result.CollectionEvent;

import java.util.List;


public record ParserResult(String gcAlgorithm, List<CollectionEvent> events, List<Double> pauseDurations,
                           List<Long> safepointTtspNs, int promotionFailures, long maxUptimeMs, int peakUsedMb,
                           int reclaimedTotalMb, int totalLines, int parsedLines, int minorCount, int mixedCount,
                           int fullCount) {
}