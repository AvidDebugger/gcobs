package net.szumigaj.gcobs.cli.telemetry.gc.parser;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;

class GcLogParserDispatcherTest {

    private final GcLogParserDispatcher dispatcher = new GcLogParserDispatcher(new G1GcLogParser(), new LegacyFallbackGcLogParser());

    @Test
    void parsesG1LogViaBufferedInputStream() throws IOException {
        String log = """
                [0ms] Using G1
                [100ms] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->8M(256M) 5.432ms
                [250ms] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 32M->12M(256M) 3.210ms
                """;
        try (BufferedReader input = new BufferedReader(new StringReader(log))) {
            ParserResult result = dispatcher.parse(input);

            assertThat(result.gcAlgorithm()).isEqualTo("G1");
            assertThat(result.events()).hasSize(2);
            assertThat(result.pauseDurations()).hasSize(2);
            assertThat(result.peakUsedMb()).isEqualTo(32);
        }
    }

    @Test
    void parsesZgcLogViaFallback() throws IOException {
        String log = """
                [0ms] Using ZGC
                [100ms] Pause Mark Start 0.234ms
                [200ms] Pause Mark End 0.456ms
                """;
        try (BufferedReader input = new BufferedReader(new StringReader(log))) {
            ParserResult result = dispatcher.parse(input);

            assertThat(result.gcAlgorithm()).isEqualTo("ZGC");
            assertThat(result.events()).hasSize(2);
            assertThat(result.pauseDurations()).hasSize(2);
        }
    }

    @Test
    void usesFallbackWhenNoAlgorithmDetected() throws IOException {
        String log = """
                [100ms] Some unknown log line
                """;
        try (BufferedReader input = new BufferedReader(new StringReader(log))) {
            ParserResult result = dispatcher.parse(input);

            assertThat(result.gcAlgorithm()).isNull();
            assertThat(result.totalLines()).isEqualTo(1);
        }
    }

    @Test
    void parserStartsFromBeginningAfterDetection_noLineLoss() throws IOException {
        // Detection reads a prefix, reset() must restore stream so parser sees from line 1.
        // If reset failed, the first GC event (on line 2) would be lost.
        String log = """
                [0ms] Using G1
                [100ms] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->8M(256M) 5.432ms
                [250ms] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 32M->12M(256M) 3.210ms
                """;
        try (BufferedReader input = new BufferedReader(new StringReader(log))) {
            ParserResult result = dispatcher.parse(input);

            assertThat(result.gcAlgorithm()).isEqualTo("G1");
            assertThat(result.events()).hasSize(2);
            assertThat(result.events().get(0).uptimeMs()).isEqualTo(100L);
            assertThat(result.events().get(1).uptimeMs()).isEqualTo(250L);
        }
    }
}
