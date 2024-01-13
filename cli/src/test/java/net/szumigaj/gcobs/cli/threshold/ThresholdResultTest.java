package net.szumigaj.gcobs.cli.threshold;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static net.szumigaj.gcobs.cli.threshold.ThresholdResult.ThresholdStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class ThresholdResultTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializesPassResult() throws Exception {
        ThresholdResult result = ThresholdResult.builder()
                .status(PASS)
                .passing(List.of(new ThresholdResult.PassingEntry("gcOverheadMaxPct", 3.0, 2.3)))
                .breaches(Collections.emptyList())
                .build();

        String json = mapper.writeValueAsString(result);
        JsonNode node = mapper.readTree(json);

        assertThat(node.get("status").asText()).isEqualTo("PASS");
        assertThat(node.get("passing")).hasSize(1);
        assertThat(node.get("passing").get(0).get("field").asText()).isEqualTo("gcOverheadMaxPct");
        assertThat(node.get("passing").get(0).get("threshold").asDouble()).isEqualTo(3.0);
        assertThat(node.get("passing").get(0).get("actual").asDouble()).isEqualTo(2.3);
    }

    @Test
    void serializesFailResult() throws Exception {
        ThresholdResult result = ThresholdResult.builder()
                .status(FAIL)
                .breaches(List.of(
                        new ThresholdResult.Breach("gcPauseP99MaxMs", 50.0, 84.2,
                                "P99 GC pause 84.2ms exceeds threshold 50ms")))
                .passing(List.of(new ThresholdResult.PassingEntry("gcOverheadMaxPct", 3.0, 2.3)))
                .build();

        String json = mapper.writeValueAsString(result);
        JsonNode node = mapper.readTree(json);

        assertThat(node.get("status").asText()).isEqualTo("FAIL");
        assertThat(node.get("breaches")).hasSize(1);
        assertThat(node.get("breaches").get(0).get("field").asText()).isEqualTo("gcPauseP99MaxMs");
        assertThat(node.get("breaches").get(0).get("message").asText()).contains("84.2ms");
    }

    @Test
    void serializesSkippedResult() throws Exception {
        ThresholdResult result = ThresholdResult.builder()
                .status(SKIPPED)
                .skipped(List.of(
                        new ThresholdResult.SkippedEntry("gcCpuMaxPct", "metric unavailable (JFR disabled)")))
                .build();

        String json = mapper.writeValueAsString(result);
        JsonNode node = mapper.readTree(json);

        assertThat(node.get("status").asText()).isEqualTo("SKIPPED");
        assertThat(node.get("skipped")).hasSize(1);
        assertThat(node.get("skipped").get(0).get("reason").asText()).contains("JFR disabled");
    }

    @Test
    void nullFieldsOmittedFromJson() throws Exception {
        ThresholdResult result = ThresholdResult.builder()
                .status(PASS)
                .passing(List.of(new ThresholdResult.PassingEntry("gcOverheadMaxPct", 3.0, 2.3)))
                .build();
        // breaches and skipped are null

        String json = mapper.writeValueAsString(result);
        JsonNode node = mapper.readTree(json);

        assertThat(node.has("breaches")).isFalse();
        assertThat(node.has("skipped")).isFalse();
    }

    @Test
    void roundTripDeserialization() throws Exception {
        ThresholdResult original = ThresholdResult.builder()
                .status(FAIL)
                .breaches(List.of(
                        new ThresholdResult.Breach("gcPauseP99MaxMs", 50.0, 84.2, "breach")))
                .passing(List.of(
                        new ThresholdResult.PassingEntry("gcOverheadMaxPct", 3.0, 2.3)))
                .skipped(List.of(
                        new ThresholdResult.SkippedEntry("gcCpuMaxPct", "unavailable")))
                .build();

        String json = mapper.writeValueAsString(original);
        ThresholdResult deserialized = mapper.readValue(json, ThresholdResult.class);

        assertThat(deserialized.status().name()).isEqualTo(FAIL.name());
        assertThat(deserialized.breaches()).hasSize(1);
        assertThat(deserialized.breaches().get(0).field()).isEqualTo("gcPauseP99MaxMs");
        assertThat(deserialized.passing()).hasSize(1);
        assertThat(deserialized.skipped()).hasSize(1);
    }
}
