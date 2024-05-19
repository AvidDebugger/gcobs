package net.szumigaj.gcobs.cli.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.szumigaj.gcobs.cli.artifact.RunManifestModel;
import net.szumigaj.gcobs.cli.telemetry.JsonWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RunAggregatorTest {

    @TempDir
    private Path tempDir;

    private final RunAggregator aggregator = new RunAggregator();
    private final ObjectMapper mapper = JsonWriter.mapper();

    @Test
    void aggregate_createsOutputDirectory_andAggregationJson() throws IOException {
        writeRunJson("run-1", List.of(benchEntry("bench-a", "success", 10.0, null, null, null)));

        aggregator.aggregate("my-spec", List.of("run-1"), tempDir, "ts");

        assertThat(tempDir.resolve("ts-aggregated").resolve("aggregation.json")).exists();
    }

    @Test
    void aggregate_setsSpecNameRunCountAndRunIds() throws IOException {
        writeRunJson("run-1", List.of(benchEntry("bench-a", "success", 1.0, null, null, null)));
        writeRunJson("run-2", List.of(benchEntry("bench-a", "success", 2.0, null, null, null)));

        aggregator.aggregate("perf-spec", List.of("run-1", "run-2"), tempDir, "ts");

        JsonNode agg = readAggregation("ts");
        assertThat(agg.get("specName").asText()).isEqualTo("perf-spec");
        assertThat(agg.get("runCount").asInt()).isEqualTo(2);
        assertThat(agg.get("runs")).hasSize(2);
        assertThat(agg.get("runs").get(0).asText()).isEqualTo("run-1");
        assertThat(agg.get("runs").get(1).asText()).isEqualTo("run-2");
    }

    @Test
    void aggregate_computesCorrectMeanAndStddev_forJmhScore() throws IOException {
        writeRunJson("run-1", List.of(benchEntry("bench-a", "success", 10.0, null, null, null)));
        writeRunJson("run-2", List.of(benchEntry("bench-a", "success", 20.0, null, null, null)));

        aggregator.aggregate("my-spec", List.of("run-1", "run-2"), tempDir, "ts");

        JsonNode jmhScore = readAggregation("ts").get("benchmarks").get("bench-a").get("jmhScore");
        assertThat(jmhScore.get("mean").asDouble()).isEqualTo(15.0, within(1e-6));
        assertThat(jmhScore.get("stddev").asDouble()).isCloseTo(Math.sqrt(50.0), within(1e-6));
        assertThat(jmhScore.get("min").asDouble()).isEqualTo(10.0, within(1e-6));
        assertThat(jmhScore.get("max").asDouble()).isEqualTo(20.0, within(1e-6));
    }

    @Test
    void aggregate_skipsRunWithMissingRunJson_withoutError() throws IOException {
        writeRunJson("run-ok", List.of(benchEntry("bench-a", "success", 5.0, null, null, null)));

        aggregator.aggregate("my-spec", List.of("run-missing", "run-ok"), tempDir, "ts");

        JsonNode agg = readAggregation("ts");

        assertThat(agg.get("runCount").asInt()).isEqualTo(2);

        JsonNode jmhScore = agg.get("benchmarks").get("bench-a").get("jmhScore");
        assertThat(jmhScore.get("mean").asDouble()).isEqualTo(5.0, within(1e-6));
    }

    @Test
    void aggregate_excludesBenchmarks_withNonSuccessStatus() throws IOException {
        writeRunJson("run-1", List.of(
                benchEntry("bench-ok", "success", 10.0, null, null, null),
                benchEntry("bench-fail", "failed", 99.0, null, null, null)
        ));

        aggregator.aggregate("my-spec", List.of("run-1"), tempDir, "ts");

        JsonNode benchmarks = readAggregation("ts").get("benchmarks");
        assertThat(benchmarks.has("bench-ok")).isTrue();
        assertThat(benchmarks.has("bench-fail")).isFalse();
    }

    @Test
    void aggregate_handlesPartialMetrics_gcFieldsAbsentFromJson() throws IOException {
        writeRunJson("run-1", List.of(benchEntry("bench-a", "success", 42.0, null, null, null)));

        aggregator.aggregate("my-spec", List.of("run-1"), tempDir, "ts");

        JsonNode bench = readAggregation("ts").get("benchmarks").get("bench-a");
        assertThat(bench.has("jmhScore")).isTrue();
        assertThat(bench.has("gcPauseP99Ms")).isFalse();
        assertThat(bench.has("gcOverheadPct")).isFalse();
        assertThat(bench.has("gcCountFull")).isFalse();
    }

    @Test
    void aggregate_aggregatesAcrossMultipleBenchmarks() throws IOException {
        writeRunJson("run-1", List.of(
                benchEntry("bench-a", "success", 10.0, null, null, null),
                benchEntry("bench-b", "success", 20.0, null, null, null)
        ));
        writeRunJson("run-2", List.of(
                benchEntry("bench-a", "success", 30.0, null, null, null),
                benchEntry("bench-b", "success", 40.0, null, null, null)
        ));

        aggregator.aggregate("my-spec", List.of("run-1", "run-2"), tempDir, "ts");

        JsonNode benchmarks = readAggregation("ts").get("benchmarks");
        assertThat(benchmarks.has("bench-a")).isTrue();
        assertThat(benchmarks.has("bench-b")).isTrue();
        assertThat(benchmarks.get("bench-a").get("jmhScore").get("mean").asDouble())
                .isEqualTo(20.0, within(1e-6));
        assertThat(benchmarks.get("bench-b").get("jmhScore").get("mean").asDouble())
                .isEqualTo(30.0, within(1e-6));
    }

    @Test
    void aggregate_skipsBenchmarksListNull_gracefully() throws IOException {
        Path runDir = tempDir.resolve("run-null");
        Files.createDirectories(runDir);
        RunManifestModel manifest = RunManifestModel.builder()
                .runId("run-null")
                .benchmarks(null)
                .build();
        mapper.writeValue(runDir.resolve("run.json").toFile(), manifest);

        // Should not throw
        aggregator.aggregate("my-spec", List.of("run-null"), tempDir, "ts");

        JsonNode benchmarks = readAggregation("ts").get("benchmarks");
        assertThat(benchmarks).isEmpty();
    }

    @Test
    void aggregate_allFourMetrics_computedCorrectly() throws IOException {
        writeRunJson("run-1", List.of(benchEntry("bench-a", "success", 1.0, 2.0, 3.0, 4)));
        writeRunJson("run-2", List.of(benchEntry("bench-a", "success", 5.0, 6.0, 7.0, 8)));

        aggregator.aggregate("my-spec", List.of("run-1", "run-2"), tempDir, "ts");

        JsonNode bench = readAggregation("ts").get("benchmarks").get("bench-a");
        assertThat(bench.get("jmhScore").get("mean").asDouble()).isEqualTo(3.0, within(1e-6));
        assertThat(bench.get("gcOverheadPct").get("mean").asDouble()).isEqualTo(4.0, within(1e-6));
        assertThat(bench.get("gcPauseP99Ms").get("mean").asDouble()).isEqualTo(5.0, within(1e-6));
        assertThat(bench.get("gcCountFull").get("mean").asDouble()).isEqualTo(6.0, within(1e-6));
    }

    private void writeRunJson(String runId, List<RunManifestModel.BenchmarkEntry> entries) throws IOException {
        Path runDir = tempDir.resolve(runId);
        Files.createDirectories(runDir);
        RunManifestModel manifest = RunManifestModel.builder()
                .runId(runId)
                .benchmarks(entries)
                .build();
        mapper.writeValue(runDir.resolve("run.json").toFile(), manifest);
    }

    private JsonNode readAggregation(String prefix) throws IOException {
        return mapper.readTree(
                tempDir.resolve(prefix + "-aggregated").resolve("aggregation.json").toFile());
    }

    private RunManifestModel.BenchmarkEntry benchEntry(String id, String status,
                                                        Double jmhScore, Double gcOverheadPct,
                                                        Double gcPauseP99Ms, Integer gcCountFull) {
        return RunManifestModel.BenchmarkEntry.builder()
                .id(id)
                .status(status)
                .jmhScore(jmhScore)
                .gcOverheadPct(gcOverheadPct)
                .gcPauseP99Ms(gcPauseP99Ms)
                .gcCountFull(gcCountFull)
                .build();
    }
}
