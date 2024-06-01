package net.szumigaj.gcobs.cli.compare;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.szumigaj.gcobs.cli.model.config.CompareConfig;
import net.szumigaj.gcobs.cli.model.config.ComparisonMetric;
import net.szumigaj.gcobs.cli.model.config.ComparisonPair;
import net.szumigaj.gcobs.cli.model.env.EnvironmentInfo;
import net.szumigaj.gcobs.cli.telemetry.JsonWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static net.szumigaj.gcobs.cli.compare.CompareResult.MetricDelta.Status.IMPROVEMENT;
import static net.szumigaj.gcobs.cli.compare.CompareResult.MetricDelta.Status.REGRESSION;

@Slf4j
@Singleton
public class ComparisonEngine {

    private static final DateTimeFormatter ISO_FMT =
            DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

    /**
     * Default metrics used when a ComparisonPair has no explicit metrics.
     */
    private static final List<ComparisonMetric> DEFAULT_METRICS = List.of(
            metricOf("gcPauseP99Ms", 20.0),
            metricOf("gcOverheadPct", 20.0),
            metricOf("jmhScore", 15.0)
    );

    /**
     * Metrics that are diagnostic-only and excluded from verdict computation.
     */
    private static final List<String> DIAGNOSTIC_METRICS = List.of("compilationTotalMs");

    /**
     * Runs intra-spec comparisons after all benchmarks in a run complete.
     *
     * @param compareConfig the compare block from the spec
     * @param runDir        the run directory
     * @param runId         the run ID
     * @param profileMode   "invariant" or "explore"
     * @return list of comparison results
     */
    public List<CompareResult> compareIntraSpec(CompareConfig compareConfig,
                                                Path runDir, String runId,
                                                String profileMode) {
        if (compareConfig == null || compareConfig.pairs() == null) {
            return Collections.emptyList();
        }

        List<CompareResult> results = new ArrayList<>();
        for (ComparisonPair pair : compareConfig.pairs()) {
            Path baseBenchDir = runDir.resolve("benchmarks").resolve(pair.base());
            Path candBenchDir = runDir.resolve("benchmarks").resolve(pair.candidate());

            CompareResult result = comparePair(pair, runDir, runDir,
                    pair.base(), pair.candidate(), profileMode, false);
            results.add(result);

            // Write compare-result.json
            try {
                Path compareDir = runDir.resolve("compare").resolve(pair.id());
                Files.createDirectories(compareDir);
                writeCompareResultJson(result, pair, compareDir, runId, runId,
                        baseBenchDir, candBenchDir, false);
            } catch (IOException e) {
                log.error("[gcobs] WARNING: Could not write compare-result.json for {}: {}",
                        pair.id(), e.getMessage());
            }
        }
        return results;
    }

    /**
     * Runs cross-run comparison between two run directories.
     *
     * @param baseRunDir      base run directory
     * @param candidateRunDir candidate run directory
     * @param benchmarkMap    mapping of base benchmark ID to candidate benchmark ID
     * @param metrics         comparison metrics (from spec or defaults)
     * @param requireEnvMatch if true, force INCONCLUSIVE on environment mismatch
     * @return comparison result
     */
    public CompareResult compareCrossRun(Path baseRunDir, Path candidateRunDir,
                                         Map<String, String> benchmarkMap,
                                         List<ComparisonMetric> metrics,
                                         boolean requireEnvMatch) throws IOException {
        // Resolve benchmark IDs
        String baseRunId = baseRunDir.getFileName().toString();
        String candRunId = candidateRunDir.getFileName().toString();

        String baseBenchId;
        String candBenchId;

        if (benchmarkMap != null && !benchmarkMap.isEmpty()) {
            var firstEntry = benchmarkMap.entrySet().iterator().next();
            baseBenchId = firstEntry.getKey();
            candBenchId = firstEntry.getValue();
        } else {
            baseBenchId = findFirstBenchmarkId(baseRunDir);
            candBenchId = findFirstBenchmarkId(candidateRunDir);
        }

        if (baseBenchId == null || candBenchId == null) {
            return CompareResult.builder()
                    .pairId("cross-run")
                    .baseBenchmarkId(baseBenchId)
                    .candidateBenchmarkId(candBenchId)
                    .verdict(ComparisonVerdict.INCONCLUSIVE)
                    .metrics(Collections.emptyList())
                    .build();
        }

        ComparisonPair pair = ComparisonPair
                .builder()
                .id("cross-run")
                .base(baseBenchId)
                .candidate(candBenchId)
                .description(String.format("Cross-run: %s/%s vs %s/%s",
                        baseRunId, baseBenchId, candRunId, candBenchId))
                .metrics(metrics != null && !metrics.isEmpty() ? metrics : null)
                .build();

        return comparePair(pair, baseRunDir, candidateRunDir,
                baseBenchId, candBenchId, "invariant", requireEnvMatch);
    }

    public CompareResult comparePair(ComparisonPair pair,
                                     Path baseRunDir, Path candidateRunDir,
                                     String baseBenchId, String candBenchId,
                                     String profileMode,
                                     boolean requireEnvMatch) {
        CompareResult.CompareResultBuilder resultBuilder = CompareResult.builder()
                .pairId(pair.id())
                .baseBenchmarkId(pair.base())
                .candidateBenchmarkId(pair.candidate())
                .description(pair.description())
                .environmentMatch(new CompareResult.EnvironmentMatch(true, Collections.emptyList()));


        Path baseBenchDir = baseRunDir.resolve("benchmarks").resolve(baseBenchId);
        Path candBenchDir = candidateRunDir.resolve("benchmarks").resolve(candBenchId);

        // Check both benchmark dirs exist
        if (anyDoesNotExits(baseBenchDir, candBenchDir) || anyDidntSucceed(baseBenchDir, candBenchDir)) {
            resultBuilder.verdict(ComparisonVerdict.INCONCLUSIVE);
            resultBuilder.metrics(Collections.emptyList());
        } else {

            // Resolve metrics to compare
            List<ComparisonMetric> metricsToCompare = (pair.metrics() != null && !pair.metrics().isEmpty())
                    ? pair.metrics() : DEFAULT_METRICS;

            // Read scoreError for confidence-aware comparison on jmhScore
            Double baseScoreError = MetricExtractor.extractScoreError(baseBenchDir);
            Double candScoreError = MetricExtractor.extractScoreError(candBenchDir);
            boolean confidenceAvailable = baseScoreError != null && candScoreError != null
                    && baseScoreError > 0 && candScoreError > 0;

            // Compute deltas
            List<CompareResult.MetricDelta> deltas = new ArrayList<>();
            for (ComparisonMetric metric : metricsToCompare) {
                Double baseVal = MetricExtractor.extractMetric(metric.name(), baseBenchDir);
                Double candVal = MetricExtractor.extractMetric(metric.name(), candBenchDir);

                Double delta = null;
                Double deltaPct = null;
                CompareResult.MetricDelta.Status status = CompareResult.MetricDelta.Status.UNKNOWN;
                boolean isDiagnostic = DIAGNOSTIC_METRICS.contains(metric.name());

                if (baseVal != null && candVal != null) {
                    delta = candVal - baseVal;
                    if (baseVal != 0.0) {
                        deltaPct = (delta / baseVal) * 100.0;
                    }
                    status = evaluateMetricStatus(delta, deltaPct, metric);
                }

                Double threshold = metric.regressionThresholdPct() != null
                        ? metric.regressionThresholdPct() : metric.regressionThresholdAbsolute();
                CompareResult.MetricDelta.ThresholdType thresholdType = metric.regressionThresholdPct() != null ? CompareResult.MetricDelta.ThresholdType.PERCENTAGE
                        : (metric.regressionThresholdAbsolute() != null ? CompareResult.MetricDelta.ThresholdType.ABSOLUTE : null);


                CompareResult.Confidence confidence = null;
                CompareResult.MetricDelta.Status metricDeltaStatus = status;

                // Confidence-aware override for jmhScore
                if ("jmhScore".equals(metric.name()) && confidenceAvailable
                        && baseVal != null && candVal != null) {
                    // Convert JMH scoreError (99.9% CI half-width) to 95% CI
                    // JMH uses t-distribution; approximate: halfCI_95 = scoreError * (1.96 / 3.291)
                    double factor = 1.96 / 3.291;
                    double baseHalfCi = baseScoreError * factor;
                    double candHalfCi = candScoreError * factor;
                    double[] baseCi = {baseVal - baseHalfCi, baseVal + baseHalfCi};
                    double[] candCi = {candVal - candHalfCi, candVal + candHalfCi};

                    // CIs overlap if max(low) < min(high)
                    boolean overlapping = Math.max(baseCi[0], candCi[0]) < Math.min(baseCi[1], candCi[1]);
                    boolean significant = !overlapping;

                    confidence = new CompareResult.Confidence(
                            "jmh-scoreError", 0.95, baseCi, candCi, significant);

                    // If CIs overlap, override to OK (not statistically significant)
                    if (overlapping && (REGRESSION.equals(status) || IMPROVEMENT.equals(status))) {
                        metricDeltaStatus = CompareResult.MetricDelta.Status.OK;
                    }
                }

                CompareResult.MetricDelta md = new CompareResult.MetricDelta(
                        metric.name(), baseVal, candVal, delta, deltaPct, metricDeltaStatus,
                        threshold, thresholdType, CompareResult.MetricDelta.Direction.LOWER_IS_BETTER,
                        isDiagnostic ? true : null, confidence);

                deltas.add(md);
            }
            resultBuilder.metrics(deltas);

            // Determine verdict
            if ("explore".equals(profileMode)) {
                resultBuilder.verdict(ComparisonVerdict.INCONCLUSIVE);
            } else {
                resultBuilder.verdict(computeVerdict(deltas));
            }

        }

        // Check environment compatibility
        if (requireEnvMatch) {
            List<String> envWarnings = checkEnvironmentCompat(baseRunDir, candidateRunDir);
            if (!envWarnings.isEmpty()) {
                resultBuilder.environmentMatch(new CompareResult.EnvironmentMatch(false, envWarnings));
                resultBuilder.verdict(ComparisonVerdict.INCONCLUSIVE);
            }
        }

        return resultBuilder.build();
    }

    private boolean anyDidntSucceed(Path baseBenchDir, Path candBenchDir) {
        return !benchmarkSucceeded(baseBenchDir) || !benchmarkSucceeded(candBenchDir);
    }

    private static boolean anyDoesNotExits(Path baseBenchDir, Path candBenchDir) {
        return !Files.isDirectory(baseBenchDir) || !Files.isDirectory(candBenchDir);
    }

    private CompareResult.MetricDelta.Status evaluateMetricStatus(Double delta, Double deltaPct,
                                                                  ComparisonMetric cm) {
        // All metrics are lower-is-better: positive delta = regression
        if (cm.regressionThresholdPct() != null && deltaPct != null) {
            if (deltaPct > cm.regressionThresholdPct()) {
                return REGRESSION;
            } else if (deltaPct < -cm.regressionThresholdPct()) {
                return IMPROVEMENT;
            }
            return CompareResult.MetricDelta.Status.OK;
        }

        if (cm.regressionThresholdAbsolute() != null && delta != null) {
            if (delta > cm.regressionThresholdAbsolute()) {
                return REGRESSION;
            } else if (delta < -cm.regressionThresholdAbsolute()) {
                return IMPROVEMENT;
            }
            return CompareResult.MetricDelta.Status.OK;
        }

        // No threshold declared, just report direction
        if (delta != null) {
            if (delta > 0) return REGRESSION;
            if (delta < 0) return IMPROVEMENT;
        }
        return CompareResult.MetricDelta.Status.OK;
    }

    private ComparisonVerdict computeVerdict(List<CompareResult.MetricDelta> deltas) {
        boolean anyRegression = false;
        boolean anyImprovement = false;

        for (var d : deltas) {
            // Diagnostic metrics don't affect verdict
            if (Boolean.TRUE.equals(d.diagnostic())) continue;
            if (REGRESSION.equals(d.status())) anyRegression = true;
            if (IMPROVEMENT.equals(d.status())) anyImprovement = true;
        }

        // Check if all non-diagnostic metrics are UNKNOWN
        boolean allUnknown = deltas.stream()
                .filter(d -> !Boolean.TRUE.equals(d.diagnostic()))
                .allMatch(d -> CompareResult.MetricDelta.Status.UNKNOWN.equals(d.status()));
        if (allUnknown) return ComparisonVerdict.INCONCLUSIVE;

        if (anyRegression) return ComparisonVerdict.REGRESSION;
        if (anyImprovement) return ComparisonVerdict.IMPROVEMENT;
        return ComparisonVerdict.OK;
    }

    private boolean benchmarkSucceeded(Path benchDir) {
        Path summaryPath = benchDir.resolve("benchmark-summary.json");
        if (!Files.exists(summaryPath)) return false;
        try {
            JsonNode root = JsonWriter.mapper().readTree(summaryPath.toFile());
            JsonNode status = root.get("status");
            return status != null && "success".equals(status.asText());
        } catch (IOException e) {
            return false;
        }
    }

    private String findFirstBenchmarkId(Path runDir) throws IOException {
        Path benchmarksDir = runDir.resolve("benchmarks");
        if (!Files.isDirectory(benchmarksDir)) return null;
        try (var stream = Files.list(benchmarksDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .findFirst()
                    .orElse(null);
        }
    }

    private List<String> checkEnvironmentCompat(Path baseRunDir, Path candidateRunDir) {
        List<String> warnings = new ArrayList<>();
        try {
            EnvironmentInfo baseEnv = readEnvironment(baseRunDir);
            EnvironmentInfo candEnv = readEnvironment(candidateRunDir);
            if (baseEnv == null || candEnv == null) {
                warnings.add("Environment info unavailable in one or both runs");
                return warnings;
            }

            if (baseEnv.availableProcessors() != candEnv.availableProcessors()) {
                warnings.add(String.format("CPU count differs: base=%d candidate=%d",
                        baseEnv.availableProcessors(), candEnv.availableProcessors()));
            }
            if (baseEnv.javaVersion() != null && candEnv.javaVersion() != null
                    && !baseEnv.javaVersion().equals(candEnv.javaVersion())) {
                warnings.add(String.format("JDK version differs: base=%s candidate=%s",
                        baseEnv.javaVersion(), candEnv.javaVersion()));
            }
            if (baseEnv.physicalMemoryMb() > 0 && candEnv.physicalMemoryMb() > 0) {
                double diff = Math.abs(baseEnv.physicalMemoryMb() - candEnv.physicalMemoryMb());
                double maxMem = Math.max(baseEnv.physicalMemoryMb(), candEnv.physicalMemoryMb());
                if (diff / maxMem > 0.20) {
                    warnings.add(String.format("Memory differs by >20%%: base=%dMB candidate=%dMB",
                            baseEnv.physicalMemoryMb(), candEnv.physicalMemoryMb()));
                }
            }
        } catch (IOException e) {
            warnings.add("Could not read environment info: " + e.getMessage());
        }
        return warnings;
    }

    private EnvironmentInfo readEnvironment(Path runDir) throws IOException {
        Path runJson = runDir.resolve("run.json");
        if (!Files.exists(runJson)) return null;
        JsonNode root = JsonWriter.mapper().readTree(runJson.toFile());
        JsonNode envNode = root.get("environment");
        if (envNode == null) return null;
        return JsonWriter.mapper().treeToValue(envNode, EnvironmentInfo.class);
    }

    private void writeCompareResultJson(CompareResult result, ComparisonPair pair,
                                        Path outputDir, String baseRunId, String candRunId,
                                        Path baseBenchDir, Path candBenchDir,
                                        boolean requireEnvMatch) throws IOException {

        CompareResultModel.DecisionContext.DecisionContextBuilder decisionContextBuilder = CompareResultModel.DecisionContext.builder()
                .thresholdPolicy("explicit-only")
                .missingThresholds(Collections.emptyList())
                .confidenceAware(false)
                .requireEnvMatch(requireEnvMatch)
                .suppressedByEnvMismatch(false);

        // Set confidence-aware fields if any metric has confidence data
        boolean hasConfidence = result.metrics() != null && result.metrics().stream()
                .anyMatch(m -> m.confidence() != null);
        if (hasConfidence) {
            decisionContextBuilder.confidenceAware(true)
                    .confidenceMethod("jmh-scoreError")
                    .confidenceLevel("0.95");
        }

        CompareResultModel.DecisionContext decisionContext = decisionContextBuilder.build();

        CompareResultModel model = CompareResultModel.builder()
                .comparisonId(pair.id())
                .description(pair.description())
                .producedAt(ISO_FMT.format(Instant.now()))
                .base(new CompareResultModel.RunRef(baseRunId, pair.base(), readJvmArgs(baseBenchDir)))
                .candidate(new CompareResultModel.RunRef(candRunId, pair.candidate(), readJvmArgs(candBenchDir)))
                .environmentMatch(result.environmentMatch())
                .decisionContext(decisionContext)
                .verdict(result.verdict())
                .metrics(result.metrics())
                .warnings(Collections.emptyList())
                .build();

        JsonWriter.write(outputDir.resolve("compare-result.json"), model);
    }

    private List<String> readJvmArgs(Path benchDir) {
        try {
            Path bsPath = benchDir.resolve("benchmark-summary.json");
            if (!Files.exists(bsPath)) return Collections.emptyList();
            JsonNode root = JsonWriter.mapper().readTree(bsPath.toFile());
            JsonNode jvm = root.path("jvm");
            JsonNode args = jvm.get("argsRequested");
            if (args == null || !args.isArray()) return Collections.emptyList();
            List<String> result = new ArrayList<>();
            args.forEach(n -> result.add(n.asText()));
            return result.isEmpty() ? Collections.emptyList() : result;
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    private static ComparisonMetric metricOf(String name, double regressionThresholdPct) {
        return ComparisonMetric
                .builder()
                .name(name)
                .regressionThresholdPct(regressionThresholdPct)
                .build();
    }
}
