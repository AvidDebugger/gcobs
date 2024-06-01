package net.szumigaj.gcobs.cli.model.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MissingMetricPolicy {
    FAIL("fail"),
    SKIP("skip");

    private final String key;

    @JsonCreator
    public static MissingMetricPolicy fromJson(String value) {
        if (value == null) return null;
        for (MissingMetricPolicy p : values()) {
            if (p.key.equalsIgnoreCase(value)) return p;
        }
        throw new IllegalArgumentException("Unknown onMissingMetric value: \"" + value + "\". Valid values: fail, skip");
    }

    @JsonValue
    public String getKey() {
        return key;
    }
}
