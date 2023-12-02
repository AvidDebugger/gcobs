package net.szumigaj.gcobs.cli.spec;

import jakarta.inject.Singleton;
import net.szumigaj.gcobs.cli.model.*;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Singleton
public class SpecValidator {
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]*$");
    private static final Set<String> VALID_PROFILES = Set.of("invariant", "explore");
    private static final Set<String> VALID_SOURCE_TYPES = Set.of("internal", "jar", "gradle");
    private static final Set<String> VALID_ON_MISSING_METRIC = Set.of("fail", "skip");

    public List<ValidationError> validate(BenchmarkRunSpec spec) {
        List<ValidationError> errors = new ArrayList<>();

        validateMetadata(spec, errors);
        validateRunConfig(spec, errors);
        validateJvmArgs(spec.jvm(), "jvm.args", errors);
        validateJmhConfig(spec.jmh(), "jmh", errors);
        validateBenchmarks(spec, errors);
        validateComparePairs(spec, errors);

        return errors;
    }

    private void validateMetadata(BenchmarkRunSpec spec, List<ValidationError> errors) {
        if (spec.metadata() == null || spec.metadata().name() == null) {
            errors.add(new ValidationError("metadata.name",
                    "metadata.name is required", null));
        } else if (!ID_PATTERN.matcher(spec.metadata().name()).matches()) {
            errors.add(new ValidationError("metadata.name",
                    "metadata.name: invalid pattern",
                    "must match ^[a-z0-9][a-z0-9-]*$, found: \"" + spec.metadata().name() + "\""));
        }
    }

    private void validateRunConfig(BenchmarkRunSpec spec, List<ValidationError> errors) {
        if (spec.run() == null) return;

        if (spec.run().profile() != null && !VALID_PROFILES.contains(spec.run().profile())) {
            errors.add(new ValidationError("run.profile",
                    "run.profile must be \"invariant\" or \"explore\"",
                    "found: \"" + spec.run().profile() + "\""));
        }

        if (spec.run().validation() != null) {
            validateRunValidation(spec.run().validation(), errors);
        }
    }

    private void validateRunValidation(ValidationConfig validation, List<ValidationError> errors) {
        if (validation.onMissingMetric() != null
                && !VALID_ON_MISSING_METRIC.contains(validation.onMissingMetric())) {
            errors.add(new ValidationError("run.validation.onMissingMetric",
                    "run.validation.onMissingMetric() must be \"fail\" or \"skip\"",
                    "found: \"" + validation.onMissingMetric() + "\""));
        }

        if (validation.minParseCoveragePct() != null) {
            int pct = validation.minParseCoveragePct();
            if (pct < 0 || pct > 100) {
                errors.add(new ValidationError("run.validation.minParseCoveragePct",
                        "run.validation.minParseCoveragePct() must be in range 0-100",
                        "found: " + pct));
            }
        }
    }

    private void validateJvmArgs(JvmConfig jvm, String fieldPrefix, List<ValidationError> errors) {
        if (jvm != null && jvm.args() != null) {
            validateForbiddenFlags(jvm.args(), fieldPrefix, errors);
        }
    }

    private void validateJmhConfig(JmhConfig jmh, String prefix, List<ValidationError> errors) {
        if (jmh != null) {
            validateJmhMinValues(jmh, prefix, errors);
        }
    }

    private void validateBenchmarks(BenchmarkRunSpec spec, List<ValidationError> errors) {
        if (spec.benchmarks() == null || spec.benchmarks().isEmpty()) {
            errors.add(new ValidationError("benchmarks",
                    "benchmarks list must be non-empty", null));
            return;
        }

        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < spec.benchmarks().size(); i++) {
            validateBenchmarkEntry(spec.benchmarks().get(i), i, seenIds, errors);
        }
    }

    private void validateBenchmarkEntry(BenchmarkEntry bench, int index,
                                        Set<String> seenIds, List<ValidationError> errors) {
        String prefix = "benchmarks[" + index + "]";

        validateBenchmarkId(bench, prefix, seenIds, errors);
        validateBenchmarkSource(bench, prefix, errors);
        validateJvmArgs(bench.jvm(), prefix + ".jvm.args", errors);
        validateJmhConfig(bench.jmh(), prefix + ".jmh", errors);
    }

    private void validateBenchmarkId(BenchmarkEntry bench, String prefix,
                                     Set<String> seenIds, List<ValidationError> errors) {
        if (bench.id() == null) {
            errors.add(new ValidationError(prefix + ".id",
                    "benchmark id is required", null));
        } else {
            if (!ID_PATTERN.matcher(bench.id()).matches()) {
                errors.add(new ValidationError(prefix + ".id",
                        "benchmark id: invalid pattern",
                        "must match ^[a-z0-9][a-z0-9-]*$, found: \"" + bench.id() + "\""));
            }
            if (!seenIds.add(bench.id())) {
                errors.add(new ValidationError(prefix + ".id",
                        "duplicate benchmark id: \"" + bench.id() + "\"",
                        "each benchmark must have a unique id"));
            }
        }
    }

    private void validateBenchmarkSource(BenchmarkEntry bench, String prefix,
                                         List<ValidationError> errors) {
        if (bench.source() == null || bench.source().type() == null) {
            errors.add(new ValidationError(prefix + ".source.type",
                    "source.type is required", null));
        } else if (!VALID_SOURCE_TYPES.contains(bench.source().type())) {
            errors.add(new ValidationError(prefix + ".source.type",
                    "source.type must be \"internal\", \"jar\", or \"gradle\"",
                    "found: \"" + bench.source().type() + "\""));
        }
    }

    private void validateComparePairs(BenchmarkRunSpec spec, List<ValidationError> errors) {
        if (spec.compare() == null || spec.compare().pairs() == null || spec.benchmarks() == null) {
            return;
        }

        Set<String> benchmarkIds = spec.benchmarks().stream()
                .map(BenchmarkEntry::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        for (int i = 0; i < spec.compare().pairs().size(); i++) {
            validateComparisonPair(spec.compare().pairs().get(i), i, benchmarkIds, errors);
        }
    }

    private void validateComparisonPair(ComparisonPair pair, int index,
                                        Set<String> benchmarkIds, List<ValidationError> errors) {
        String prefix = "compare.pairs[" + index + "]";

        if (pair.base() != null && !benchmarkIds.contains(pair.base())) {
            errors.add(new ValidationError(prefix + ".base",
                    "compare pair references unknown benchmark id: \"" + pair.base() + "\"",
                    "must reference an existing benchmark id"));
        }
        if (pair.candidate() != null && !benchmarkIds.contains(pair.candidate())) {
            errors.add(new ValidationError(prefix + ".candidate",
                    "compare pair references unknown benchmark id: \"" + pair.candidate() + "\"",
                    "must reference an existing benchmark id"));
        }

        if (pair.metrics() != null) {
            for (int j = 0; j < pair.metrics().size(); j++) {
                validateComparisonMetric(pair.metrics().get(j), prefix, j, errors);
            }
        }
    }

    private void validateComparisonMetric(ComparisonMetric metric, String prefix,
                                          int index, List<ValidationError> errors) {
        if (metric.regressionThresholdPct() != null
                && metric.regressionThresholdAbsolute() != null) {
            errors.add(new ValidationError("%s.metrics[%d]".formatted(prefix, index),
                    "comparison metric cannot have both regressionThresholdPct and regressionThresholdAbsolute",
                    "use one or the other, not both"));
        }
    }

    private void validateForbiddenFlags(List<String> args, String field,
                                        List<ValidationError> errors) {
        for (String arg : args) {
            if (arg.startsWith("-Xlog:gc")
                    || arg.startsWith("-XX:StartFlightRecording")
                    || arg.startsWith("-XX:FlightRecorderOptions")) {
                errors.add(new ValidationError(field,
                        "forbidden JVM flag: \"%s\"".formatted(arg),
                        "gcobs injects GC logging and JFR flags automatically"));
            }
        }
    }

    private void validateJmhMinValues(JmhConfig jmh, String prefix,
                                      List<ValidationError> errors) {
        if (jmh.warmupIterations() != null && jmh.warmupIterations() < 1) {
            errors.add(new ValidationError(prefix + ".warmupIterations",
                    "warmupIterations must be >= 1",
                    "found: " + jmh.warmupIterations()));
        }
        if (jmh.measurementIterations() != null && jmh.measurementIterations() < 1) {
            errors.add(new ValidationError(prefix + ".measurementIterations",
                    "measurementIterations must be >= 1",
                    "found: " + jmh.measurementIterations()));
        }
        if (jmh.forks() != null && jmh.forks() < 1) {
            errors.add(new ValidationError(prefix + ".forks",
                    "forks must be >= 1",
                    "found: " + jmh.forks()));
        }
    }

    public List<String> getRigorWarnings(BenchmarkRunSpec spec) {
        List<String> warnings = new ArrayList<>();

        if (spec.jmh() != null) {
            checkRigor(spec.jmh(), "jmh", warnings);
        }

        if (spec.benchmarks() != null) {
            for (int i = 0; i < spec.benchmarks().size(); i++) {
                BenchmarkEntry bench = spec.benchmarks().get(i);
                if (bench.jmh() != null) {
                    String prefix = bench.id() != null
                            ? "benchmarks[" + bench.id() + "].jmh"
                            : "benchmarks[" + i + "].jmh";
                    checkRigor(bench.jmh(), prefix, warnings);
                }
            }
        }

        return warnings;
    }

    private void checkRigor(JmhConfig jmh, String prefix, List<String> warnings) {
        if (jmh.warmupIterations() != null && jmh.warmupIterations() < 3) {
            warnings.add(prefix + ".warmupIterations=" + jmh.warmupIterations()
                    + " (< 3 may produce unreliable results)");
        }
        if (jmh.measurementIterations() != null && jmh.measurementIterations() < 3) {
            warnings.add(prefix + ".measurementIterations=" + jmh.measurementIterations()
                    + " (< 3 may produce unreliable results)");
        }
        if (jmh.forks() != null && jmh.forks() < 2) {
            warnings.add(prefix + ".forks=" + jmh.forks()
                    + " (< 2 may produce unreliable results)");
        }
    }
}
