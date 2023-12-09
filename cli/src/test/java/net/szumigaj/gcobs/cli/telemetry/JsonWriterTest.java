package net.szumigaj.gcobs.cli.telemetry;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JsonWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesPrettyPrintedJson() throws IOException {
        record Sample(String name, int value) {}

        Path output = tempDir.resolve("test.json");
        JsonWriter.write(output, new Sample("hello", 42));

        String json = Files.readString(output);
        assertThat(json)
                .contains("\"name\" : \"hello\"")
                .contains("\"value\" : 42")
                .contains("\n");
    }

    @Test
    void excludesNullFields() throws IOException {
        @JsonInclude(JsonInclude.Include.NON_NULL)
        class WithNull {
            public String present = "yes";
            public String absent = null;
        }

        Path output = tempDir.resolve("test.json");
        JsonWriter.write(output, new WithNull());

        String json = Files.readString(output);
        assertThat(json)
                .contains("present")
                .doesNotContain("absent");
    }
}
