package net.szumigaj.gcobs.cli.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public record BenchmarkRunSpec(String apiVersion, String kind, Metadata metadata, RunConfig run, JvmConfig jvm,
                               JmhConfig jmh, ObservabilityConfig observability, OutputConfig output,
                               List<BenchmarkEntry> benchmarks, CompareConfig compare) {
}
