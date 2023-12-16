package net.szumigaj.gcobs.cli.artifacts;

import net.szumigaj.gcobs.cli.artifact.JmhResultParser;
import net.szumigaj.gcobs.cli.artifact.JmhScore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class JmhResultParserTest {

    @TempDir
    Path tempDir;

    @Test
    void parseValidJmhResults() throws IOException {
        // Copy fixture to tempDir
        try (InputStream in = getClass().getResourceAsStream("/jmh/jmh-results-sample.json")) {
            Files.copy(in, tempDir.resolve("jmh-results.json"));
        }

        JmhScore score = JmhResultParser.parse(tempDir);

        assertThat(score.score()).isCloseTo(0.012345, within(0.000001));
        assertThat(score.scoreError()).isCloseTo(0.001234, within(0.000001));
        assertThat(score.scoreUnit()).isEqualTo("ms/op");
        assertThat(score.scoreConfidenceInterval()).isNotNull();
        assertThat(score.scoreConfidenceInterval()).hasSize(2);
        assertThat(score.scoreConfidenceInterval()[0]).isCloseTo(0.011111, within(0.000001));
        assertThat(score.scoreConfidenceInterval()[1]).isCloseTo(0.013579, within(0.000001));
    }

    @Test
    void parseMissingFileReturnsEmpty() {
        JmhScore score = JmhResultParser.parse(tempDir);

        assertThat(score).isSameAs(JmhScore.EMPTY);
        assertThat(score.score()).isNull();
        assertThat(score.scoreError()).isNull();
        assertThat(score.scoreUnit()).isNull();
        assertThat(score.scoreConfidenceInterval()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not valid json {{{", "[]", "[{\"benchmark\": \"test\"}]"})
    void parseMalformedJsonReturnsEmpty(String jsonString) throws IOException {
        Files.writeString(tempDir.resolve("jmh-results.json"), jsonString);

        JmhScore score = JmhResultParser.parse(tempDir);

        assertThat(score).isSameAs(JmhScore.EMPTY);
    }
}
