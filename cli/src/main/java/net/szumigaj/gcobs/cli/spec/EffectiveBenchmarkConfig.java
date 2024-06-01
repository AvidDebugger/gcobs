package net.szumigaj.gcobs.cli.spec;

import lombok.Builder;
import net.szumigaj.gcobs.cli.model.config.SourceConfig;
import net.szumigaj.gcobs.cli.model.config.ThresholdsConfig;

import java.util.List;
import java.util.Map;

/**
 * Fully merged configuration for a single benchmark after applying
 * top-level defaults, per-benchmark overrides, and built-in defaults.
 */
@Builder
public record EffectiveBenchmarkConfig(String id, SourceConfig source, List<String> jvmArgs, Map<String, String> jvmEnv,
                                       int warmupIterations, int measurementIterations, int forks, int threads,
                                       String jmhIncludes, Map<String, String> params, boolean jfrEnabled,
                                       String jfrSettings, String gcLogTags, boolean timeseriesEnabled,
                                       boolean phaseBoundariesEnabled, String phaseBoundariesSource,
                                       boolean heapDumpOnOutOfMemoryError, ThresholdsConfig thresholds) {
}
