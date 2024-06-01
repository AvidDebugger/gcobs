package net.szumigaj.gcobs.cli.model.env;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.szumigaj.gcobs.cli.telemetry.JsonWriter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentInfoTest {

    private final ObjectMapper mapper = JsonWriter.mapper();

    @Test
    void builderSetsJavaHomePath() {
        EnvironmentInfo info = minimalBuilder()
                .javaHomePath("/usr/lib/jvm/java-17-openjdk-amd64")
                .build();

        assertThat(info.javaHomePath()).isEqualTo("/usr/lib/jvm/java-17-openjdk-amd64");
    }

    @Test
    void builderAllowsNullForNewFields() {
        EnvironmentInfo info = minimalBuilder()
                .jdkMajorVersion(null)
                .jvmDistribution(null)
                .javaHomePath(null)
                .build();

        assertThat(info.jdkMajorVersion()).isNull();
        assertThat(info.jvmDistribution()).isNull();
        assertThat(info.javaHomePath()).isNull();
    }

    @Test
    void nullJdkMajorVersionOmittedFromJson() throws Exception {
        EnvironmentInfo info = minimalBuilder()
                .jdkMajorVersion(null)
                .build();

        JsonNode node = mapper.readTree(mapper.writeValueAsString(info));

        assertThat(node.has("jdkMajorVersion")).isFalse();
    }

    @Test
    void nullJvmDistributionOmittedFromJson() throws Exception {
        EnvironmentInfo info = minimalBuilder()
                .jvmDistribution(null)
                .build();

        JsonNode node = mapper.readTree(mapper.writeValueAsString(info));

        assertThat(node.has("jvmDistribution")).isFalse();
    }

    @Test
    void nullJavaHomePathOmittedFromJson() throws Exception {
        EnvironmentInfo info = minimalBuilder()
                .javaHomePath(null)
                .build();

        JsonNode node = mapper.readTree(mapper.writeValueAsString(info));

        assertThat(node.has("javaHomePath")).isFalse();
    }

    @Test
    void newFieldsPresentInJsonWhenSet() throws Exception {
        EnvironmentInfo info = minimalBuilder()
                .jdkMajorVersion(17)
                .jvmDistribution("Adoptium")
                .javaHomePath("/usr/lib/jvm/adoptium-17")
                .build();

        JsonNode node = mapper.readTree(mapper.writeValueAsString(info));

        assertThat(node.has("jdkMajorVersion")).isTrue();
        assertThat(node.get("jdkMajorVersion").asInt()).isEqualTo(17);

        assertThat(node.has("jvmDistribution")).isTrue();
        assertThat(node.get("jvmDistribution").asText()).isEqualTo("Adoptium");

        assertThat(node.has("javaHomePath")).isTrue();
        assertThat(node.get("javaHomePath").asText()).isEqualTo("/usr/lib/jvm/adoptium-17");
    }

    private EnvironmentInfo.EnvironmentInfoBuilder minimalBuilder() {
        return EnvironmentInfo.builder()
                .availableProcessors(1)
                .physicalMemoryMb(0);
    }
}
