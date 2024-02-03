package net.szumigaj.gcobs.cli.compare;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MetricExtractorTest {

    @TempDir
    private Path tempDir;

    @Test
    void extractScoreError_returnsValue_whenPresent() throws IOException {
        Files.writeString(tempDir.resolve("benchmark-summary.json"),
                "{\"jmh\":{\"score\":1.0,\"scoreError\":0.05}}");

        Double result = MetricExtractor.extractScoreError(tempDir);

        assertThat(result).isEqualTo(0.05);
    }

    @Test
    void extractScoreError_returnsNull_whenScoreErrorMissing() throws IOException {
        Files.writeString(tempDir.resolve("benchmark-summary.json"),
                "{\"jmh\":{\"score\":1.0}}");

        Double result = MetricExtractor.extractScoreError(tempDir);

        assertThat(result).isNull();
    }

    @Test
    void extractScoreError_returnsNull_whenFileAbsent() {
        Double result = MetricExtractor.extractScoreError(tempDir);

        assertThat(result).isNull();
    }
}
