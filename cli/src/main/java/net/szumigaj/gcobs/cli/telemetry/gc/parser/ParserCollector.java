package net.szumigaj.gcobs.cli.telemetry.gc.parser;

import lombok.Setter;
import net.szumigaj.gcobs.cli.model.result.CollectionEvent;

import java.util.ArrayList;
import java.util.List;


class ParserCollector {
    @Setter
    private String gcAlgorithm;
    private final List<CollectionEvent> events = new ArrayList<>();
    private final List<Double> pauseDurations = new ArrayList<>();
    private final List<Long> safepointTtspNs = new ArrayList<>();
    private int promotionFailures;
    private long maxUptimeMs;
    private int peakUsedMb;
    private int reclaimedTotalMb;
    private int totalLines;
    private int parsedLines;
    private int minorCount;
    private int mixedCount;
    private int fullCount;

    public ParserCollector() {
    }

    public ParserCollector(String gcAlgorithm) {
        this.gcAlgorithm = gcAlgorithm;
    }

    public void incrementTotalLines() {
        totalLines++;
    }

    public void incrementParsedLines() {
        parsedLines++;
    }

    public void addEvents(CollectionEvent collectionEvent) {
        this.events.add(collectionEvent);
    }

    public ParserResult toResult() {
        return new ParserResult(gcAlgorithm, events, pauseDurations,
                safepointTtspNs, promotionFailures, maxUptimeMs, peakUsedMb, reclaimedTotalMb,
                totalLines, parsedLines, minorCount, mixedCount, fullCount);
    }

    public void addPauseDurations(double durationMs) {
        this.pauseDurations.add(durationMs);
    }

    public void incrementReclaimedTotalMb(int reclaimedMb) {
        this.reclaimedTotalMb += reclaimedMb;
    }

    public void registerPeakUsedMb(int beforeMb) {
        this.peakUsedMb = Math.max(this.peakUsedMb, beforeMb);
    }

    public void incrementMinorCount() {
        this.minorCount++;
    }

    public void incrementMixedCount() {
        this.mixedCount++;
    }

    public void incrementFullCount() {
        this.fullCount++;
    }

    public void addSafepointTtspNs(long safepointTtspNs) {
        this.safepointTtspNs.add(safepointTtspNs);
    }

    public void incrementPromotionFailures() {
        this.promotionFailures++;
    }

    public void registerUptimeMs(long uptimeMs) {
        maxUptimeMs = Math.max(maxUptimeMs, uptimeMs);
    }
}
