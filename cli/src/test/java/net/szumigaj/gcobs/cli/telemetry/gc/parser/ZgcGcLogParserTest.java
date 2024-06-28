package net.szumigaj.gcobs.cli.telemetry.gc.parser;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;

class ZgcGcLogParserTest {

    private final ZgcGcLogParser parser = new ZgcGcLogParser();

    @Test
    void parsesZgcPausePhases() throws IOException {
        String log = """
                [0ms] Initializing The Z Garbage Collector
                [100ms] GC(0) Pause Mark Start 0.015ms
                [200ms] GC(0) Pause Mark End 0.008ms
                [300ms] GC(0) Pause Relocate Start 0.012ms
                """;
        try (BufferedReader input = new BufferedReader(new StringReader(log))) {
            ParserResult result = parser.parse(input);

            assertThat(result.gcAlgorithm()).isEqualTo("ZGC");
            assertThat(result.events()).hasSize(3);
            assertThat(result.pauseDurations()).hasSize(3);
            assertThat(result.minorCount()).isEqualTo(3);
            assertThat(result.events().get(0).cause()).isEqualTo("Mark Start");
            assertThat(result.events().get(1).cause()).isEqualTo("Mark End");
            assertThat(result.events().get(2).cause()).isEqualTo("Relocate Start");
            assertThat(result.maxUptimeMs()).isEqualTo(300L);
        }
    }

    @Test
    void extractsSafepointTtsp() throws IOException {
        String log = """
                [0ms] Initializing The Z Garbage Collector
                [100ms] Pause Mark Start 0.010ms
                [100ms] Reaching safepoint: 50000 ns
                """;
        try (BufferedReader input = new BufferedReader(new StringReader(log))) {
            ParserResult result = parser.parse(input);

            assertThat(result.safepointTtspNs()).hasSize(1);
            assertThat(result.safepointTtspNs().get(0)).isEqualTo(50000L);
        }
    }

    @Test
    void handlesForkMarkers() throws IOException {
        String log = """
                # === Fork: gc-1.log ===
                [0ms] Initializing The Z Garbage Collector
                [1000ms] Pause Mark Start 0.010ms

                # === Fork: gc-2.log ===
                [0ms] Initializing The Z Garbage Collector
                [1500ms] Pause Mark Start 0.012ms
                """;
        try (BufferedReader input = new BufferedReader(new StringReader(log))) {
            ParserResult result = parser.parse(input);

            assertThat(result.maxUptimeMs()).isEqualTo(2500L);
            assertThat(result.events()).hasSize(2);
        }
    }
}
