package net.szumigaj.gcobs.cli.model.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

import java.util.Map;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public record BenchmarkEntry(String id, String description, SourceConfig source, JvmConfig jvm, JmhConfig jmh,
                             Map<String, String> params, ObservabilityConfig observability,
                             ThresholdsConfig thresholds) {
}
