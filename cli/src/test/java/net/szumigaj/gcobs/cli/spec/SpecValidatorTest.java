package net.szumigaj.gcobs.cli.spec;

import net.szumigaj.gcobs.cli.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpecValidatorTest {

    private static final String SOURCE_TYPE_INTERNAL = "internal";

    private final SpecValidator validator = new SpecValidator();

    private BenchmarkRunSpec validSpec() {
        return BenchmarkRunSpec.builder()
                .metadata(Metadata.builder()
                        .name("test-spec")
                        .build())
                .benchmarks(List.of(
                        BenchmarkEntry.builder()
                                .id("noop-g1")
                                .source(SourceConfig.builder()
                                        .type(SOURCE_TYPE_INTERNAL)
                                        .module("benchmark-noop-jmh")
                                        .build())
                                .build()
                ))
                .build();
    }

    @Test
    void validSpecReturnsNoErrors() {
        assertThat(validator.validate(validSpec())).isEmpty();
    }

    @Test
    void invalidMetadataName() {
        var spec = validSpec().toBuilder()
                .metadata(validSpec().metadata().toBuilder()
                        .name("Invalid_Name")
                        .build())
                .build();
        var errors = validator.validate(spec);
        assertThat(errors).extracting(ValidationError::field).contains("metadata.name");
    }

    @Test
    void nullMetadataName() {
        var spec = validSpec().toBuilder()
                .metadata(validSpec().metadata().toBuilder()
                        .name(null)
                        .build())
                .build();
        var errors = validator.validate(spec);
        assertThat(errors).extracting(ValidationError::field).contains("metadata.name");
    }

    @Test
    void invalidBenchmarkId() {
        var spec = validSpec().toBuilder()
                .benchmarks(List.of(
                        validSpec().benchmarks().get(0).toBuilder()
                                .id("Bad_Id")
                                .build()
                ))
                .build();
        var errors = validator.validate(spec);
        assertThat(errors).extracting(ValidationError::field).contains("benchmarks[0].id");
    }

    @Test
    void invalidProfile() {
        var spec = validSpec().toBuilder()
                .run(RunConfig.builder()
                        .profile("debug")
                        .build())
                .build();
        var errors = validator.validate(spec);
        assertThat(errors).extracting(ValidationError::field).contains("run.profile");
    }

    @Test
    void emptyBenchmarks() {
        var spec = validSpec().toBuilder()
                .benchmarks(List.of())
                .build();
        var errors = validator.validate(spec);
        assertThat(errors).extracting(ValidationError::field).contains("benchmarks");
    }

    @Test
    void nullBenchmarks() {
        var spec = validSpec().toBuilder()
                .benchmarks(null)
                .build();
        var errors = validator.validate(spec);
        assertThat(errors).extracting(ValidationError::field).contains("benchmarks");
    }

    @Test
    void duplicateBenchmarkIds() {
        var bench1 = BenchmarkEntry.builder()
                .id("same-id")
                .source(SourceConfig.builder()
                        .type(SOURCE_TYPE_INTERNAL)
                        .module("benchmark-noop-jmh")
                        .build())
                .build();
        var bench2 = BenchmarkEntry.builder()
                .id("same-id")
                .source(SourceConfig.builder()
                        .type(SOURCE_TYPE_INTERNAL)
                        .module("benchmark-noop-jmh")
                        .build())
                .build();
        var spec = validSpec().toBuilder()
                .benchmarks(List.of(bench1, bench2))
                .build();
        var errors = validator.validate(spec);
        assertThat(errors).anyMatch(e -> e.field().equals("benchmarks[1].id"));
    }

    @Test
    void invalidSourceType() {
        var spec = validSpec().toBuilder()
                .benchmarks(List.of(
                        validSpec().benchmarks().get(0).toBuilder()
                                .source(validSpec().benchmarks().get(0).source().toBuilder()
                                        .type(null)  // Set to null since "docker" doesn't exist as enum
                                        .build())
                                .build()
                ))
                .build();
        var errors = validator.validate(spec);
        assertThat(errors).extracting(ValidationError::field).contains("benchmarks[0].source.type");
    }

    @Test
    void forbiddenJvmFlags_topLevel() {
        var spec = validSpec().toBuilder()
                .jvm(JvmConfig.builder()
                        .args(List.of("-Xms256m", "-Xlog:gc*:file=gc.log"))
                        .build())
                .build();
        var errors = validator.validate(spec);
        assertThat(errors).extracting(ValidationError::field).contains("jvm.args");
    }

    @Test
    void forbiddenJvmFlags_perBenchmark() {
        var spec = validSpec().toBuilder()
                .benchmarks(List.of(
                        validSpec().benchmarks().get(0).toBuilder()
                                .jvm(JvmConfig.builder()
                                        .args(List.of("-XX:StartFlightRecording=filename=test.jfr"))
                                        .build())
                                .build()
                ))
                .build();
        var errors = validator.validate(spec);
        assertThat(errors).extracting(ValidationError::field).contains("benchmarks[0].jvm.args");
    }

    @Test
    void flightRecorderOptions() {
        var spec = validSpec().toBuilder()
                .jvm(JvmConfig.builder()
                        .args(List.of("-XX:FlightRecorderOptions=stackdepth=128"))
                        .build())
                .build();
        var errors = validator.validate(spec);
        assertThat(errors).extracting(ValidationError::field).contains("jvm.args");
    }

    @Test
    void jmhMinValues_topLevel() {
        var spec = validSpec().toBuilder()
                .jmh(JmhConfig.builder()
                        .warmupIterations(0)
                        .forks(-1)
                        .measurementIterations(0)
                        .build())
                .build();
        var errors = validator.validate(spec);
        assertThat(errors).hasSize(3)
                .extracting(ValidationError::field)
                .containsExactlyInAnyOrder("jmh.warmupIterations", "jmh.forks", "jmh.measurementIterations");
    }

    @Test
    void jmhMinValues_perBenchmark() {
        var spec = validSpec().toBuilder()
                .benchmarks(List.of(
                        validSpec().benchmarks().get(0).toBuilder()
                                .jmh(JmhConfig.builder()
                                        .forks(0)
                                        .build())
                                .build()
                ))
                .build();
        var errors = validator.validate(spec);
        assertThat(errors).extracting(ValidationError::field).contains("benchmarks[0].jmh.forks");
    }

    @Test
    void comparePairReferencesUnknownId() {
        var pair = ComparisonPair.builder()
                .id("test-pair")
                .base("noop-g1")
                .candidate("nonexistent-bench")
                .build();
        var spec = validSpec().toBuilder()
                .compare(CompareConfig.builder()
                        .pairs(List.of(pair))
                        .build())
                .build();
        var errors = validator.validate(spec);
        assertThat(errors).anyMatch(e -> e.field().contains("candidate"));
    }

    @Test
    void bothThresholdTypes() {
        var metric = ComparisonMetric.builder()
                .name("gcOverheadPct")
                .regressionThresholdPct(10.0)
                .regressionThresholdAbsolute(5.0)
                .build();
        var pair = ComparisonPair.builder()
                .id("test-pair")
                .base("noop-g1")
                .candidate("noop-g1")
                .metrics(List.of(metric))
                .build();
        var spec = validSpec().toBuilder()
                .compare(CompareConfig.builder()
                        .pairs(List.of(pair))
                        .build())
                .build();
        var errors = validator.validate(spec);
        assertThat(errors).anyMatch(e -> e.field().equals("compare.pairs[0].metrics[0]"));
    }

    @Test
    void invalidOnMissingMetric() {
        var spec = validSpec().toBuilder()
                .run(RunConfig.builder()
                        .validation(ValidationConfig.builder()
                                .onMissingMetric("ignore")
                                .build())
                        .build())
                .build();
        var errors = validator.validate(spec);
        assertThat(errors).anyMatch(e -> e.field().equals("run.validation.onMissingMetric"));
    }

    @Test
    void parseCoverageOutOfRange() {
        var spec = validSpec().toBuilder()
                .run(RunConfig.builder()
                        .validation(ValidationConfig.builder()
                                .minParseCoveragePct(150)
                                .build())
                        .build())
                .build();
        var errors = validator.validate(spec);
        assertThat(errors).extracting(ValidationError::field).contains("run.validation.minParseCoveragePct");
    }

    @Test
    void parseCoverageNegative() {
        var spec = validSpec().toBuilder()
                .run(RunConfig.builder()
                        .validation(ValidationConfig.builder()
                                .minParseCoveragePct(-1)
                                .build())
                        .build())
                .build();
        var errors = validator.validate(spec);
        assertThat(errors).anyMatch(e -> e.field().equals("run.validation.minParseCoveragePct"));
    }

    @Test
    void multipleErrorsCollected() {
        var spec = BenchmarkRunSpec.builder()
                .metadata(Metadata.builder()
                        .name("BAD")
                        .build())
                .benchmarks(List.of())
                .build();

        var errors = validator.validate(spec);
        assertThat(errors).hasSizeGreaterThanOrEqualTo(2);
        assertThat(errors.stream().map(ValidationError::field).toList())
                .contains("metadata.name", "benchmarks");
    }

    @Test
    void rigorWarnings_lowForks() {
        var spec = validSpec().toBuilder()
                .jmh(JmhConfig.builder()
                        .forks(1)
                        .build())
                .build();
        var warnings = validator.getRigorWarnings(spec);
        assertThat(warnings).anyMatch(w -> w.contains("forks=1"));
    }

    @Test
    void rigorWarnings_lowWarmup() {
        var spec = validSpec().toBuilder()
                .jmh(JmhConfig.builder()
                        .warmupIterations(2)
                        .build())
                .build();
        var warnings = validator.getRigorWarnings(spec);
        assertThat(warnings).anyMatch(w -> w.contains("warmupIterations=2"));
    }

    @Test
    void rigorWarnings_perBenchmark() {
        var spec = validSpec().toBuilder()
                .benchmarks(List.of(
                        validSpec().benchmarks().get(0).toBuilder()
                                .jmh(JmhConfig.builder()
                                        .measurementIterations(1)
                                        .build())
                                .build()
                ))
                .build();
        var warnings = validator.getRigorWarnings(spec);
        assertThat(warnings).anyMatch(w -> w.contains("measurementIterations=1"));
    }

    @Test
    void validProfile_invariant() {
        var spec = validSpec().toBuilder()
                .run(RunConfig.builder()
                        .profile("invariant")
                        .build())
                .build();
        assertThat(validator.validate(spec)).isEmpty();
    }

    @Test
    void validProfile_explore() {
        var spec = validSpec().toBuilder()
                .run(RunConfig.builder()
                        .profile("explore")
                        .build())
                .build();
        assertThat(validator.validate(spec)).isEmpty();
    }

    @Test
    void validOnMissingMetric_skip() {
        var spec = validSpec().toBuilder()
                .run(RunConfig.builder()
                        .validation(ValidationConfig.builder()
                                .onMissingMetric("skip")
                                .build())
                        .build())
                .build();
        assertThat(validator.validate(spec)).isEmpty();
    }
}
