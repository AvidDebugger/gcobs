package net.szumigaj.gcobs.cli.model.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SourceType {
    GRADLE("gradle"),
    INTERNAL("internal"),
    JAR("jar");

    private final String key;

    @JsonCreator
    public static SourceType fromJson(String value) {
        if (value == null) return null;
        for (SourceType t : values()) {
            if (t.key.equalsIgnoreCase(value)) return t;
        }
        throw new IllegalArgumentException("Unknown source type: \"" + value + "\". Valid values: internal, jar, gradle");
    }

    @JsonValue
    public String getKey() {
        return key;
    }
}
