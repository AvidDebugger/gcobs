package net.szumigaj.gcobs.cli.telemetry;

import net.szumigaj.gcobs.cli.model.JfrSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JfrExtractorTest {

    private final JfrExtractor extractor = new JfrExtractor();

    @Test
    void noJfrFilesReturnsNull(@TempDir Path tempDir) throws IOException {
        JfrSummary result = extractor.extract(tempDir, "test-bench", "test-run");

        assertThat(result).isNull();
        assertThat(tempDir.resolve("jfr-summary.json")).doesNotExist();
    }

    @Test
    void allCorruptJfrFilesReturnNull(@TempDir Path tempDir) throws IOException {
        // Write garbage bytes, JFR parser will throw IOException
        Files.write(tempDir.resolve("profile-1.jfr"), new byte[]{0x00, 0x01, 0x02, 0x03});

        JfrSummary result = extractor.extract(tempDir, "test-bench", "test-run");

        assertThat(result).isNull();
        assertThat(tempDir.resolve("jfr-summary.json")).doesNotExist();
    }
}
