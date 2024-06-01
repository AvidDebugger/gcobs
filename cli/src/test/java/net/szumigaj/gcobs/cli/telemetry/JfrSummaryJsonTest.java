package net.szumigaj.gcobs.cli.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.szumigaj.gcobs.cli.model.result.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class JfrSummaryJsonTest {

    private final ObjectMapper mapper = JsonWriter.mapper();

    @Test
    void nullNewFieldsOmittedFromJson() throws Exception {
        JfrSummary summary = JfrSummary.builder()
                .benchmarkId("bench-1")
                .runId("run-1")
                .forkCount(1)
                .build();

        String json = mapper.writeValueAsString(summary);
        JsonNode node = mapper.readTree(json);

        assertThat(node.has("allocationProfile")).isFalse();
        assertThat(node.has("memoryPools")).isFalse();
        assertThat(node.has("compilation")).isFalse();
        assertThat(node.has("contention")).isFalse();
    }

    @Test
    void allNewFieldsSerializedWhenPresent() throws Exception {
        JfrSummary summary = JfrSummary.builder()
                .benchmarkId("bench-2")
                .runId("run-2")
                .forkCount(2)
                .allocationProfile(AllocationProfile.builder()
                        .totalEvents(500)
                        .outsideTlabEvents(200)
                        .inNewTlabEvents(300)
                        .topClassesByCount(List.of(
                                new AllocationClassEntry("java.lang.String", 150, 1_200_000L)))
                        .build())
                .memoryPools(MemoryPools.builder()
                        .metaspace(MemoryPools.Metaspace.builder()
                                .usedMaxMb(64.5)
                                .committedMaxMb(80.0)
                                .reservedMb(128.0)
                                .build())
                        .build())
                .compilation(Compilation.builder()
                        .count(1024)
                        .totalMs(4500.0)
                        .maxMs(120.0)
                        .osrCount(10)
                        .build())
                .contention(Contention.builder()
                        .monitorEvents(30)
                        .parkEvents(50)
                        .totalBlockedMs(200.0)
                        .maxBlockedMs(15.0)
                        .topMonitorClasses(List.of(
                                new ContentionClassEntry("java.util.concurrent.locks.ReentrantLock", 20, 180.0)))
                        .build())
                .build();

        String json = mapper.writeValueAsString(summary);
        JsonNode node = mapper.readTree(json);

        // allocationProfile
        assertThat(node.has("allocationProfile")).isTrue();
        assertThat(node.get("allocationProfile").get("totalEvents").asInt()).isEqualTo(500);
        assertThat(node.get("allocationProfile").get("topClassesByCount").isArray()).isTrue();

        // memoryPools
        assertThat(node.has("memoryPools")).isTrue();
        assertThat(node.get("memoryPools").has("metaspace")).isTrue();
        assertThat(node.get("memoryPools").get("metaspace").get("usedMaxMb").asDouble()).isEqualTo(64.5);

        // compilation
        assertThat(node.has("compilation")).isTrue();
        assertThat(node.get("compilation").get("count").asInt()).isEqualTo(1024);
        assertThat(node.get("compilation").get("osrCount").asInt()).isEqualTo(10);

        // contention
        assertThat(node.has("contention")).isTrue();
        assertThat(node.get("contention").get("monitorEvents").asInt()).isEqualTo(30);
        assertThat(node.get("contention").get("parkEvents").asInt()).isEqualTo(50);
        assertThat(node.get("contention").has("topMonitorClasses")).isTrue();
    }

    @Test
    void unknownFieldsIgnoredOnDeserialization() {
        String json = """
                {
                  "benchmarkId": "bench-x",
                  "runId": "run-x",
                  "forkCount": 1,
                  "futureField": "some-value-from-future-schema-version"
                }
                """;

        assertThatNoException().isThrownBy(() -> {
            JfrSummary summary = mapper.readValue(json, JfrSummary.class);
            assertThat(summary.benchmarkId()).isEqualTo("bench-x");
            assertThat(summary.runId()).isEqualTo("run-x");
        });
    }

    @Test
    void roundTripWithAllNewFields() throws Exception {
        JfrSummary original = JfrSummary.builder()
                .benchmarkId("bench-rt")
                .runId("run-rt")
                .forkCount(3)
                .compilation(Compilation.builder()
                        .count(512)
                        .totalMs(2200.0)
                        .maxMs(50.0)
                        .osrCount(5)
                        .build())
                .memoryPools(MemoryPools.builder()
                        .metaspace(MemoryPools.Metaspace.builder()
                                .usedMaxMb(48.0)
                                .build())
                        .build())
                .build();

        String json = mapper.writeValueAsString(original);
        JfrSummary deserialized = mapper.readValue(json, JfrSummary.class);

        assertThat(deserialized.benchmarkId()).isEqualTo("bench-rt");
        assertThat(deserialized.forkCount()).isEqualTo(3);
        assertThat(deserialized.compilation().count()).isEqualTo(512);
        assertThat(deserialized.compilation().osrCount()).isEqualTo(5);
        assertThat(deserialized.memoryPools().metaspace().usedMaxMb()).isEqualTo(48.0);
    }
}
