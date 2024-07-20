package net.szumigaj.gcobs.cli.telemetry.gc.parser;

import jakarta.inject.Singleton;
import net.szumigaj.gcobs.cli.model.result.CollectionEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fallback parser for non-specialized GC algorithms (Parallel, Serial, Shenandoah, Epsilon)
 * and unknown log formats. G1 and ZGC use dedicated parsers.
 */
@Singleton
public class LegacyFallbackGcLogParser {

    // GC Pause events: handles both G1 format (two paren groups) and Parallel/Serial (one paren group)
    private static final Pattern GC_PAUSE = Pattern.compile(
            "Pause (Young|Mixed|Full).*?\\(([^)]+)\\)(?:.*?\\(([^)]+)\\))?.*?(\\d+)M->(\\d+)M\\((\\d+)M\\).*?([0-9.,]+)ms");

    private static final Pattern SAFEPOINT_TTSP = Pattern.compile(
            "Reaching safepoint: (\\d+) ns");

    // Shenandoah STW pauses: Pause Init Mark|Final Mark|... N.Nms
    private static final Pattern SHENANDOAH_PAUSE = Pattern.compile(
            "Pause (Init Mark|Final Mark|Init Update Refs|Final Update Refs|Degenerated GC).*?([0-9.]+)ms");

    private static final Pattern PROMOTION_FAILURE = Pattern.compile("Promotion failed");

    private static final Pattern GC_ALGORITHM = Pattern.compile(
            "Using (G1|ZGC|Parallel|Serial|Shenandoah|Epsilon)");

    private static final Pattern UPTIME = Pattern.compile("\\[(\\d+)ms\\]");

    private static final Pattern FORK_MARKER = Pattern.compile("^# === Fork:");

    public ParserResult parse(BufferedReader input) throws IOException {
        ParserCollector parserCollector = new ParserCollector();
        int eventSeq = 0;
        long currentForkMaxUptime = 0;
        long totalDurationMs = 0;

        String line;
        while ((line = input.readLine()) != null) {
            if (line.isEmpty()) {
                continue;
            }

            if (FORK_MARKER.matcher(line).find()) {
                totalDurationMs = handleForkMarker(currentForkMaxUptime, totalDurationMs);
                currentForkMaxUptime = 0;
                continue;
            }

            parserCollector.incrementTotalLines();
            long uptime = extractUptime(line);
            currentForkMaxUptime = Math.max(currentForkMaxUptime, uptime);

            boolean matched = tryParseGcAlgorithm(line, parserCollector)
                    || tryParseSafepointTtsp(line, parserCollector)
                    || tryParsePromotionFailure(line, parserCollector);

            if (!matched) {
                int nextSeq = eventSeq + 1;
                matched = tryParseGcPause(line, parserCollector, nextSeq, uptime)
                        || tryParseShenandoahPause(line, parserCollector, nextSeq, uptime);
                if (matched) {
                    eventSeq = nextSeq;
                }
            }

            if (matched) {
                parserCollector.incrementParsedLines();
            }
        }

        parserCollector.registerUptimeMs(totalDurationMs + Math.max(0, currentForkMaxUptime));
        return parserCollector.toResult();
    }

    private long handleForkMarker(long currentForkMaxUptime, long totalDurationMs) {
        return currentForkMaxUptime > 0 ? totalDurationMs + currentForkMaxUptime : totalDurationMs;
    }

    private long extractUptime(String line) {
        Matcher matcher = UPTIME.matcher(line);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0;
    }

    private boolean tryParseGcAlgorithm(String line, ParserCollector parserCollector) {
        Matcher matcher = GC_ALGORITHM.matcher(line);
        if (matcher.find()) {
            parserCollector.setGcAlgorithm(matcher.group(1));
            return true;
        }
        return false;
    }

    private boolean tryParseGcPause(String line, ParserCollector parserCollector, int eventSeq, long uptime) {
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

        parserCollector.addEvents(new CollectionEvent(eventSeq, type, cause, beforeMb, afterMb, capacityMb, durationMs, uptime));
        parserCollector.addPauseDurations(durationMs);
        parserCollector.incrementReclaimedTotalMb(Math.max(0, beforeMb - afterMb));
        parserCollector.registerPeakUsedMb(beforeMb);

        switch (type) {
            case "Young" -> parserCollector.incrementMinorCount();
            case "Mixed" -> parserCollector.incrementMixedCount();
            case "Full" -> parserCollector.incrementFullCount();
        }
        return true;
    }

    private boolean tryParseShenandoahPause(String line, ParserCollector parserCollector, int eventSeq, long uptime) {
        // Shenandoah STW pauses
        Matcher shenandoahMatcher = SHENANDOAH_PAUSE.matcher(line);
        if (!shenandoahMatcher.find()) {
            return false;
        }
        double durationMs = Double.parseDouble(shenandoahMatcher.group(2));
        String phase = shenandoahMatcher.group(1);
        boolean isFull = "Degenerated GC".equals(phase);

        eventSeq++;
        String type = isFull ? "Full" : "STW-Minor";
        parserCollector.addEvents(new CollectionEvent(
                eventSeq, type, phase, 0, 0, 0,
                durationMs, uptime));
        parserCollector.addPauseDurations(durationMs);

        if (isFull) parserCollector.incrementFullCount();
        else parserCollector.incrementMinorCount();

        return true;
    }

    private boolean tryParseSafepointTtsp(String line, ParserCollector parserCollector) {
        Matcher matcher = SAFEPOINT_TTSP.matcher(line);
        if (matcher.find()) {
            parserCollector.addSafepointTtspNs(Long.parseLong(matcher.group(1)));
            return true;
        }
        return false;
    }

    private boolean tryParsePromotionFailure(String line, ParserCollector parserCollector) {
        if (PROMOTION_FAILURE.matcher(line).find()) {
            parserCollector.incrementPromotionFailures();
            return true;
        }
        return false;
    }
}
