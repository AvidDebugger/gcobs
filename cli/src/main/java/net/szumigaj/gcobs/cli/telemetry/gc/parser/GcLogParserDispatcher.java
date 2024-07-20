package net.szumigaj.gcobs.cli.telemetry.gc.parser;

import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dispatches to algorithm-specific parsers based on GC algorithm detection.
 * Each registered strategy declares what it supports via {@link GcLogParserStrategy#supports(String)}.
 * Falls back to {@link LegacyFallbackGcLogParser} when no strategy matches.
 * Detection reads only a bounded prefix; the selected parser receives the stream
 * from the start (via mark/reset) so no lines are lost.
 */
@Singleton
@RequiredArgsConstructor
public class GcLogParserDispatcher {

    private static final int DETECTION_READ_LIMIT = 64 * 1024;
    private static final int DETECTION_MAX_LINES = 100;

    private static final Pattern GC_ALGORITHM = Pattern.compile(
            "(Using G1|ZGC|Parallel|Serial|Shenandoah|Epsilon|Initializing The Z Garbage Collector)");

    private final List<GcLogParserStrategy> strategies;
    private final LegacyFallbackGcLogParser fallback;

    /**
     * Parses GC log from stream, selecting the appropriate strategy based on detected GC algorithm.
     * Detection uses a bounded prefix; the parser starts from the beginning of the stream.
     *
     * @param input the GC log stream
     * @return populated ParserResult
     * @throws IOException if reading fails
     */
    public ParserResult parse(BufferedReader input) throws IOException {
        input.mark(DETECTION_READ_LIMIT);
        GcAlgorithm algorithm = detectAlgorithm(input);
        input.reset();

        GcLogParserStrategy selected = strategies.stream()
                .filter(s -> s.supports(algorithm))
                .findFirst()
                .orElse(null);

        return selected != null ? selected.parse(input) : fallback.parse(input);
    }

    private GcAlgorithm detectAlgorithm(BufferedReader input) throws IOException {
        String line;
        int lineCount = 0;
        while ((line = input.readLine()) != null && lineCount < DETECTION_MAX_LINES) {
            lineCount++;
            Matcher matcher = GC_ALGORITHM.matcher(line);
            if (matcher.find()) {
                return GcAlgorithm.fromLogToken(matcher.group(1));
            }
        }
        return GcAlgorithm.UNKNOWN;
    }
}
