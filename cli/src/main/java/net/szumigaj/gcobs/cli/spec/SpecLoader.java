package net.szumigaj.gcobs.cli.spec;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.inject.Singleton;
import lombok.Builder;
import net.szumigaj.gcobs.cli.model.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Singleton
public class SpecLoader {
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public BenchmarkRunSpec load(Path specPath) throws IOException {
        return YAML_MAPPER.readValue(specPath.toFile(), BenchmarkRunSpec.class);
    }

    public EffectiveBenchmarkConfig getEffective(BenchmarkRunSpec spec, int index) {
        BenchmarkEntry bench = spec.benchmarks().get(index);

        List<String> jvmArgs = mergeJvmArgs(spec.jvm(), bench.jvm());
        Map<String, String> jvmEnv = mergeJvmEnv(spec.jvm(), bench.jvm());
        JmhMergedConfig jmh = mergeJmhConfig(spec.jmh(), bench.jmh());
        Map<String, String> params = mergeParams(bench.params());
        ObservabilityMergedConfig observability = mergeObservabilityConfig(
                spec.observability(), bench.observability());

        return EffectiveBenchmarkConfig.builder()
                .id(bench.id())
                .source(bench.source())
                .jvmArgs(jvmArgs)
                .jvmEnv(jvmEnv)
                .warmupIterations(jmh.warmupIterations)
                .measurementIterations(jmh.measurementIterations)
                .forks(jmh.forks)
                .threads(jmh.threads)
                .jmhIncludes(jmh.includes)
                .params(params)
                .jfrEnabled(observability.jfrEnabled)
                .jfrSettings(observability.jfrSettings)
                .gcLogTags(observability.gcLogTags)
                .timeseriesEnabled(observability.timeseriesEnabled)
                .phaseBoundariesEnabled(observability.phaseBoundariesEnabled)
                .phaseBoundariesSource(observability.phaseBoundariesSource)
                .heapDumpOnOutOfMemoryError(observability.heapDumpOnOutOfMemoryError)
                .thresholds(bench.thresholds())
                .build();
    }

    private List<String> mergeJvmArgs(JvmConfig specJvm, JvmConfig benchJvm) {
        if (benchJvm != null && benchJvm.args() != null) {
            return List.copyOf(benchJvm.args());
        }
        if (specJvm != null && specJvm.args() != null) {
            return List.copyOf(specJvm.args());
        }
        return List.of();
    }

    private Map<String, String> mergeJvmEnv(JvmConfig specJvm, JvmConfig benchJvm) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (specJvm != null && specJvm.env() != null) {
            merged.putAll(specJvm.env());
        }
        if (benchJvm != null && benchJvm.env() != null) {
            merged.putAll(benchJvm.env());
        }
        return Collections.unmodifiableMap(merged);
    }

    private JmhMergedConfig mergeJmhConfig(JmhConfig specJmh, JmhConfig benchJmh) {
        return new JmhMergedConfig(
                resolveInt(benchJmh != null ? benchJmh.warmupIterations() : null,
                        specJmh != null ? specJmh.warmupIterations() : null, 5),
                resolveInt(benchJmh != null ? benchJmh.measurementIterations() : null,
                        specJmh != null ? specJmh.measurementIterations() : null, 5),
                resolveInt(benchJmh != null ? benchJmh.forks() : null,
                        specJmh != null ? specJmh.forks() : null, 3),
                resolveInt(benchJmh != null ? benchJmh.threads() : null,
                        specJmh != null ? specJmh.threads() : null, 1),
                benchJmh != null && benchJmh.includes() != null ? benchJmh.includes()
                        : (specJmh != null ? specJmh.includes() : null)
        );
    }

    private Map<String, String> mergeParams(Map<String, String> benchParams) {
        return benchParams != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(benchParams))
                : Map.of();
    }

    private ObservabilityMergedConfig mergeObservabilityConfig(
            ObservabilityConfig specObs, ObservabilityConfig benchObs) {
        return ObservabilityMergedConfig.builder()
                .jfrEnabled(resolveBool(getJfrEnabled(benchObs), getJfrEnabled(specObs), true))
                .jfrSettings(resolveString(getJfrSettings(benchObs), getJfrSettings(specObs), "profile"))
                .gcLogTags(resolveString(getGcLogTags(specObs), null, "gc*,safepoint*,gc+promotion"))
                .timeseriesEnabled(resolveBool(getTimeseriesEnabled(benchObs), getTimeseriesEnabled(specObs), false))
                .phaseBoundariesEnabled(resolveBool(getPhaseBoundariesEnabled(benchObs), getPhaseBoundariesEnabled(specObs), false))
                .phaseBoundariesSource(resolveString(getPhaseBoundariesSource(benchObs), getPhaseBoundariesSource(specObs), null))
                .heapDumpOnOutOfMemoryError(resolveBool(getHeapDumpOnOutOfMemoryErrorEnabled(benchObs), getHeapDumpOnOutOfMemoryErrorEnabled(specObs), false))
                .build();
    }

    private Boolean getJfrEnabled(ObservabilityConfig obs) {
        return obs != null && obs.jfr() != null ? obs.jfr().enabled() : null;
    }

    private String getJfrSettings(ObservabilityConfig obs) {
        return obs != null && obs.jfr() != null ? obs.jfr().settings() : null;
    }

    private String getGcLogTags(ObservabilityConfig obs) {
        return obs != null && obs.gcLog() != null ? obs.gcLog().tags() : null;
    }

    private Boolean getTimeseriesEnabled(ObservabilityConfig obs) {
        return obs != null && obs.timeseries() != null ? obs.timeseries().enabled() : null;
    }

    private Boolean getPhaseBoundariesEnabled(ObservabilityConfig obs) {
        return obs != null && obs.phaseBoundaries() != null ? obs.phaseBoundaries().enabled() : null;
    }

    private String getPhaseBoundariesSource(ObservabilityConfig obs) {
        return obs != null && obs.phaseBoundaries() != null ? obs.phaseBoundaries().source() : null;
    }

    private Boolean getHeapDumpOnOutOfMemoryErrorEnabled(ObservabilityConfig obs) {
        return obs != null && obs.heapDumpOnOutOfMemoryError() != null ? obs.heapDumpOnOutOfMemoryError().enabled() : null;
    }

    private static int resolveInt(Integer benchLevel, Integer topLevel, int defaultVal) {
        if (benchLevel != null) return benchLevel;
        if (topLevel != null) return topLevel;
        return defaultVal;
    }

    private static boolean resolveBool(Boolean benchLevel, Boolean topLevel, boolean defaultVal) {
        if (benchLevel != null) return benchLevel;
        if (topLevel != null) return topLevel;
        return defaultVal;
    }

    private static String resolveString(String benchLevel, String topLevel, String defaultVal) {
        if (benchLevel != null) return benchLevel;
        if (topLevel != null) return topLevel;
        return defaultVal;
    }

    private record JmhMergedConfig(int warmupIterations, int measurementIterations,
                                   int forks, int threads, String includes) {}

    @Builder
    private record ObservabilityMergedConfig(boolean jfrEnabled, String jfrSettings,
                                             String gcLogTags, boolean timeseriesEnabled,
                                             boolean phaseBoundariesEnabled, String phaseBoundariesSource, boolean heapDumpOnOutOfMemoryError) {}
}
