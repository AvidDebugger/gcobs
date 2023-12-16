package net.szumigaj.gcobs.cli.telemetry;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Path;

public final class JsonWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private JsonWriter() {}

    public static void write(Path file, Object value) throws IOException {
        MAPPER.writeValue(file.toFile(), value);
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
