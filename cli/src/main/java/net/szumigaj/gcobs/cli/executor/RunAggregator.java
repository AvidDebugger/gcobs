package net.szumigaj.gcobs.cli.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.szumigaj.gcobs.cli.artifact.RunManifestModel;
import net.szumigaj.gcobs.cli.model.AggregationResult;
import net.szumigaj.gcobs.cli.telemetry.JsonWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;


@Slf4j
@Singleton
public class RunAggregator {

    @Inject
    private JsonWriter jsonWriter;

    public void aggregate(String specName, List<String> runIds,
                                  Path runsDir, String prefix) throws IOException {
        ObjectMapper mapper = JsonWriter.mapper();

        // Collect metrics per benchmark across runs
        // benchmarkId -> metricName -> list of values
        Map<String, List<Double>> jmhScores = new LinkedHashMap<>();
        Map<String, List<Double>> gcOverheads = new LinkedHashMap<>();
        Map<String, List<Double>> gcPauseP99s = new LinkedHashMap<>();
        Map<String, List<Double>> gcFullCounts = new LinkedHashMap<>();

        for (String runId : runIds) {
            Path runJson = runsDir.resolve(runId).resolve("run.json");
            if (!Files.exists(runJson)) {
                log.warn("WARNING: run.json not found for {}, skipping", runId);
                continue;
            }

            RunManifestModel manifest = mapper.readValue(runJson.toFile(), RunManifestModel.class);
            if (manifest.benchmarks() == null) continue;

            for (RunManifestModel.BenchmarkEntry entry : manifest.benchmarks()) {
                if (!"success".equals(entry.status())) continue;

                if (entry.jmhScore() != null) {
                    jmhScores.computeIfAbsent(entry.id(), k -> new ArrayList<>()).add(entry.jmhScore());
                }
                if (entry.gcOverheadPct() != null) {
                    gcOverheads.computeIfAbsent(entry.id(), k -> new ArrayList<>()).add(entry.gcOverheadPct());
                }
                if (entry.gcPauseP99Ms() != null) {
                    gcPauseP99s.computeIfAbsent(entry.id(), k -> new ArrayList<>()).add(entry.gcPauseP99Ms());
                }
                if (entry.gcCountFull() != null) {
                    gcFullCounts.computeIfAbsent(entry.id(), k -> new ArrayList<>()).add((double) entry.gcCountFull());
                }
            }
        }

        // Build all known benchmark IDs (preserving order)
        Set<String> allBenchmarkIds = new LinkedHashSet<>();
        allBenchmarkIds.addAll(jmhScores.keySet());
        allBenchmarkIds.addAll(gcOverheads.keySet());
        allBenchmarkIds.addAll(gcPauseP99s.keySet());
        allBenchmarkIds.addAll(gcFullCounts.keySet());

        Map<String, AggregationResult.BenchmarkAggregation> resultBenchmarks = new LinkedHashMap<>();

        for (String benchId : allBenchmarkIds) {
            AggregationResult.BenchmarkAggregation agg = AggregationResult.BenchmarkAggregation.builder()
                    .jmhScore(AggregationResult.MetricStats.compute(jmhScores.get(benchId)))
                    .gcOverheadPct(AggregationResult.MetricStats.compute(gcOverheads.get(benchId)))
                    .gcPauseP99Ms(AggregationResult.MetricStats.compute(gcPauseP99s.get(benchId)))
                    .gcCountFull(AggregationResult.MetricStats.compute(gcFullCounts.get(benchId)))
                    .build();
            resultBenchmarks.put(benchId, agg);
        }
        
        // Build aggregation result
        AggregationResult result = AggregationResult.builder()
                .specName(specName)
                .runCount(runIds.size())
                .runs(runIds)
                .benchmarks(resultBenchmarks)
                .build();

        // Write aggregation.json
        Path aggDir = runsDir.resolve(prefix + "-aggregated");
        Files.createDirectories(aggDir);
        JsonWriter.write(aggDir.resolve("aggregation.json"), result);

        // Print summary
        log.info("Aggregation complete: {} runs, {} benchmarks",
                runIds.size(), allBenchmarkIds.size());
        log.info("Output: {}/aggregation.json", aggDir);

        for (String benchId : allBenchmarkIds) {
            AggregationResult.BenchmarkAggregation agg = result.benchmarks().get(benchId);
            log.info("  {}:", benchId);
            if (agg.jmhScore() != null) {
                log.info(" jmhScore={}±{}", agg.jmhScore().mean(), agg.jmhScore().stddev());
            }
            if (agg.gcPauseP99Ms() != null) {
                log.info(" P99={}±{}", agg.gcPauseP99Ms().mean(), agg.gcPauseP99Ms().stddev());
            }
            if (agg.gcOverheadPct() != null) {
                log.info(" overhead={}%±{}%", agg.gcOverheadPct().mean(), agg.gcOverheadPct().stddev());
            }
        }
    }
}
