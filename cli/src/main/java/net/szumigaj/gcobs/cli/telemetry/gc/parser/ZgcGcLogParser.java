package net.szumigaj.gcobs.cli.telemetry.gc.parser;

import jakarta.inject.Singleton;
import net.szumigaj.gcobs.cli.model.result.CollectionEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ZGC-specific GC log parser. Extracts ZGC pause events (Mark Start, Mark End, Relocate Start),
 * safepoints, and promotion failures. Mirrors G1GcLogParser structure for consistent metric collection.
 */
@Singleton
public class ZgcGcLogParser implements GcLogParserStrategy {

    // ZGC format: [uptime] GC(cycle) Pause Mark Start 0.015ms, Pause Mark End 0.008ms, Pause Relocate Start 0.012ms
    private static final Pattern ZGC_PAUSE = Pattern.compile(
            "Pause (Mark Start|Mark End|Relocate Start).*?([0-9.,]+)ms");

    private static final Pattern SAFEPOINT_TTSP = Pattern.compile(
            "Reaching safepoint: (\\d+) ns");

    private static final Pattern PROMOTION_FAILURE = Pattern.compile("Promotion failed");

    private static final Pattern UPTIME = Pattern.compile("\\[(\\d+)ms\\]");

    private static final Pattern FORK_MARKER = Pattern.compile("^# === Fork:");

    @Override
    public boolean supports(GcAlgorithm algorithm) {
        return algorithm == GcAlgorithm.ZGC;
    }

    @Override
    public ParserResult parse(BufferedReader input) throws IOException {
        ParserCollector parserCollector = new ParserCollector("ZGC");
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

            boolean matched = tryParseSafepointTtsp(line, parserCollector)
                    || tryParsePromotionFailure(line, parserCollector);

            if (!matched) {
                int nextSeq = eventSeq + 1;
                matched = tryParseZgcPause(line, parserCollector, nextSeq, uptime);
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

    private boolean tryParseZgcPause(String line, ParserCollector parserCollector, int eventSeq, long uptime) {
        Matcher matcher = ZGC_PAUSE.matcher(line);
        if (!matcher.find()) {
            return false;
        }

        String phase = matcher.group(1);
        double durationMs = Double.parseDouble(matcher.group(2).replace(",", "."));

        parserCollector.addEvents(new CollectionEvent(eventSeq, "STW-Minor", phase, 0, 0, 0, durationMs, uptime));
        parserCollector.addPauseDurations(durationMs);
        parserCollector.incrementMinorCount();
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
