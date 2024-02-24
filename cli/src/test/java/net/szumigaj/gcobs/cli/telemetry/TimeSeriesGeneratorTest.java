package net.szumigaj.gcobs.cli.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TimeSeriesGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    private Path tempDir;
    
    private final TimeSeriesGenerator generator = new TimeSeriesGenerator();

    @Test
    void noStdoutLog_doesNotCreateOutput() throws IOException {
        generator.generate(tempDir);

        assertThat(tempDir.resolve("metrics-timeseries.jsonl")).doesNotExist();
    }

    @Test
    void noIterationLines_doesNotCreateOutput() throws IOException {
        Files.writeString(tempDir.resolve("jmh.stdout.log"), "some jmh output without iterations\n");

        generator.generate(tempDir);

        assertThat(tempDir.resolve("metrics-timeseries.jsonl")).doesNotExist();
    }

    @Test
    void warmupAndMeasurementIterations_noGcLog_createsJsonl() throws IOException {
        Files.writeString(tempDir.resolve("jmh.stdout.log"), """
                # Warmup Iteration   1: 0.015000 ms/op
                Iteration   1: 0.015234 ms/op
                Iteration   2: 0.016100 ms/op
                """);

        generator.generate(tempDir);

        Path output = tempDir.resolve("metrics-timeseries.jsonl");
        assertThat(output).exists();
        List<String> lines = Files.readAllLines(output);
        assertThat(lines).hasSize(3);

        JsonNode warmup = MAPPER.readTree(lines.get(0));
        assertThat(warmup.get("iteration").asInt()).isEqualTo(1);
        assertThat(warmup.get("phase").asText()).isEqualTo("warmup");
        assertThat(warmup.get("jmhScore").asDouble()).isEqualTo(0.015, within(1e-6));
        assertThat(warmup.get("gcPauseCount").asInt()).isZero();
        assertThat(warmup.get("gcPauseTotalMs").asDouble()).isZero();

        JsonNode m1 = MAPPER.readTree(lines.get(1));
        assertThat(m1.get("phase").asText()).isEqualTo("measurement");
        assertThat(m1.get("iteration").asInt()).isEqualTo(1);

        JsonNode m2 = MAPPER.readTree(lines.get(2));
        assertThat(m2.get("phase").asText()).isEqualTo("measurement");
        assertThat(m2.get("iteration").asInt()).isEqualTo(2);
    }

    @Test
    void scientificNotationScore_parsedCorrectly() throws IOException {
        Files.writeString(tempDir.resolve("jmh.stdout.log"), "Iteration   1: 1.5E-3 ms/op\n");

        generator.generate(tempDir);

        List<String> lines = Files.readAllLines(tempDir.resolve("metrics-timeseries.jsonl"));
        assertThat(lines).hasSize(1);
        JsonNode node = MAPPER.readTree(lines.get(0));
        assertThat(node.get("jmhScore").asDouble()).isEqualTo(0.0015, within(1e-9));
    }

    @Test
    void gcForkSeparatorLines_areIgnored() throws IOException {
        Files.writeString(tempDir.resolve("jmh.stdout.log"), "Iteration   1: 0.010 ms/op\n");
        Files.writeString(tempDir.resolve("gc.log"), """
                # === Fork: 1 ===
                [0.100s][info][gc] GC(0) Pause Young (Normal) 24M->8M(256M) 5.432ms
                """);

        generator.generate(tempDir);

        List<String> lines = Files.readAllLines(tempDir.resolve("metrics-timeseries.jsonl"));
        assertThat(lines).hasSize(1);
        JsonNode node = MAPPER.readTree(lines.get(0));
        assertThat(node.get("gcPauseCount").asInt()).isEqualTo(1);
        assertThat(node.get("gcPauseTotalMs").asDouble()).isEqualTo(5.4, within(0.1));
    }

    @Test
    void gcEventsDistributedAcrossMeasurementIterations() throws IOException {
        Files.writeString(tempDir.resolve("jmh.stdout.log"), """
                # Warmup Iteration   1: 0.010 ms/op
                Iteration   1: 0.010 ms/op
                Iteration   2: 0.011 ms/op
                """);
        Files.writeString(tempDir.resolve("gc.log"), """
                [0.100s][info][gc] GC(0) Pause Young (Normal) 24M->8M(256M) 5.000ms
                [0.200s][info][gc] GC(1) Pause Young (Normal) 32M->12M(256M) 6.000ms
                """);

        generator.generate(tempDir);

        List<String> lines = Files.readAllLines(tempDir.resolve("metrics-timeseries.jsonl"));
        assertThat(lines).hasSize(3);

        JsonNode warmup = MAPPER.readTree(lines.get(0));
        assertThat(warmup.get("gcPauseCount").asInt()).isZero();

        JsonNode m1 = MAPPER.readTree(lines.get(1));
        assertThat(m1.get("gcPauseCount").asInt()).isEqualTo(1);
        assertThat(m1.get("gcPauseTotalMs").asDouble()).isEqualTo(5.0, within(0.1));

        JsonNode m2 = MAPPER.readTree(lines.get(2));
        assertThat(m2.get("gcPauseCount").asInt()).isEqualTo(1);
        assertThat(m2.get("gcPauseTotalMs").asDouble()).isEqualTo(6.0, within(0.1));
    }

    @Test
    void multipleGcEventsPerIteration_aggregated() throws IOException {
        Files.writeString(tempDir.resolve("jmh.stdout.log"), "Iteration   1: 0.010 ms/op\n");
        Files.writeString(tempDir.resolve("gc.log"), """
                [0.100s][info][gc] GC(0) Pause Young (Normal) 24M->8M(256M) 5.000ms
                [0.200s][info][gc] GC(1) Pause Young (Normal) 32M->12M(256M) 3.000ms
                """);

        generator.generate(tempDir);

        List<String> lines = Files.readAllLines(tempDir.resolve("metrics-timeseries.jsonl"));
        assertThat(lines).hasSize(1);
        JsonNode node = MAPPER.readTree(lines.get(0));
        assertThat(node.get("gcPauseCount").asInt()).isEqualTo(2);
        assertThat(node.get("gcPauseTotalMs").asDouble()).isEqualTo(8.0, within(0.1));
    }
}
