package net.szumigaj.gcobs.cli.compare;

import com.fasterxml.jackson.databind.JsonNode;
import net.szumigaj.gcobs.cli.telemetry.JsonWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Extracts metric values by name from benchmark artifact JSON files.
 * Returns null when the metric is unavailable.
 */
public final class MetricExtractor {

    private MetricExtractor() {}

    /**
     * Extracts a named metric from the benchmark directory's JSON artifacts.
     *
     * @param metricName the canonical metric name
     * @param benchDir   the benchmark directory containing gc-summary.json, etc.
     * @return the metric value, or null if unavailable
     */
    public static Double extractMetric(String metricName, Path benchDir) {
        return switch (metricName) {
            case "gcOverheadPct" -> readGcSummaryDouble(benchDir, "gcOverheadPct");
            case "gcPauseP99Ms" -> readGcSummaryPause(benchDir, "p99Ms");
            case "gcPauseP95Ms" -> readGcSummaryPause(benchDir, "p95Ms");
            case "gcPauseMaxMs" -> readGcSummaryPause(benchDir, "maxMs");
            case "gcCountFull" -> readGcSummaryPauseInt(benchDir, "countFull");
            case "heapPeakUsedMb" -> readGcSummaryHeapInt(benchDir, "peakUsedMb");
            case "gcCpuPct" -> readGcSummaryDouble(benchDir, "gcCpuPct");
            case "jmhScore" -> readBenchmarkSummaryJmhScore(benchDir);
            case "allocationStalls" -> readJfrSummaryInt(benchDir, "allocationStalls");
            case "compilationTotalMs" -> readJfrCompilationTotalMs(benchDir);
            default -> null;
        };
    }

    private static Double readGcSummaryDouble(Path benchDir, String field) {
        JsonNode root = readJson(benchDir, "gc-summary.json");
        if (root == null) return null;
        JsonNode node = root.get(field);
        return (node != null && !node.isNull()) ? node.asDouble() : null;
    }

    private static Double readGcSummaryPause(Path benchDir, String field) {
        JsonNode root = readJson(benchDir, "gc-summary.json");
        if (root == null) return null;
        JsonNode pause = root.path("pause");
        JsonNode node = pause.get(field);
        return (node != null && !node.isNull()) ? node.asDouble() : null;
    }

    private static Double readGcSummaryPauseInt(Path benchDir, String field) {
        JsonNode root = readJson(benchDir, "gc-summary.json");
        if (root == null) return null;
        JsonNode pause = root.path("pause");
        JsonNode node = pause.get(field);
        return (node != null && !node.isNull()) ? (double) node.asInt() : null;
    }

    private static Double readGcSummaryHeapInt(Path benchDir, String field) {
        JsonNode root = readJson(benchDir, "gc-summary.json");
        if (root == null) return null;
        JsonNode heap = root.path("heap");
        JsonNode node = heap.get(field);
        return (node != null && !node.isNull()) ? (double) node.asInt() : null;
    }

    private static Double readBenchmarkSummaryJmhScore(Path benchDir) {
        JsonNode root = readJson(benchDir, "benchmark-summary.json");
        if (root == null) return null;
        JsonNode jmh = root.path("jmh");
        JsonNode score = jmh.get("score");
        return (score != null && !score.isNull()) ? score.asDouble() : null;
    }

    private static Double readJfrSummaryInt(Path benchDir, String field) {
        JsonNode root = readJson(benchDir, "jfr-summary.json");
        if (root == null) return null;
        JsonNode node = root.get(field);
        return (node != null && !node.isNull()) ? (double) node.asInt() : null;
    }

    private static Double readJfrCompilationTotalMs(Path benchDir) {
        JsonNode root = readJson(benchDir, "jfr-summary.json");
        if (root == null) return null;
        JsonNode compilation = root.path("compilation");
        JsonNode totalMs = compilation.get("totalMs");
        return (totalMs != null && !totalMs.isNull()) ? totalMs.asDouble() : null;
    }

    private static JsonNode readJson(Path benchDir, String filename) {
        Path file = benchDir.resolve(filename);
        if (!Files.exists(file)) return null;
        try {
            return JsonWriter.mapper().readTree(file.toFile());
        } catch (IOException e) {
            return null;
        }
    }

    public static Double extractScoreError(Path benchDir) {
        JsonNode root = readJson(benchDir, "benchmark-summary.json");
        if (root == null) return null;
        JsonNode jmh = root.path("jmh");
        JsonNode scoreError = jmh.get("scoreError");
        return (scoreError != null && !scoreError.isNull()) ? scoreError.asDouble() : null;
    }
}
