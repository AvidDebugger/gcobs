package net.szumigaj.gcobs.cli.telemetry;

import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.szumigaj.gcobs.cli.model.*;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;

/**
 * Extracts GC telemetry from JFR recordings using the native JDK JFR API.
 * Reads all profile-*.jfr files in a benchmark directory, aggregates across forks,
 * and writes jfr-summary.json (schemaVersion: 1).
 */
@Slf4j
@Singleton
public class JfrExtractor {

    // VM flag keys to extract as effective flags
    private static final Set<String> GC_FLAG_PREFIXES = Set.of(
            "ParallelGCThreads", "ConcGCThreads", "G1HeapRegionSize",
            "SoftRefLRUPolicyMSPerMB", "UseG1GC", "UseZGC", "UseParallelGC",
            "UseSerialGC", "UseShenandoahGC", "MaxHeapSize", "InitialHeapSize",
            "MaxGCPauseMillis", "G1NewSizePercent", "G1MaxNewSizePercent"
    );

    /**
     * Extracts JFR telemetry and writes jfr-summary.json.
     *
     * @return the JfrSummary, or null if no JFR files found
     */
    public JfrSummary extract(Path benchDir, String benchmarkId, String runId)
            throws IOException {
        List<Path> jfrFiles;
        try (Stream<Path> files = Files.list(benchDir)) {
            jfrFiles = files
                    .filter(p -> p.getFileName().toString().matches("profile-.*\\.jfr"))
                    .sorted()
                    .toList();
        }

        if (jfrFiles.isEmpty()) {
            return null;
        }

        List<ForkData> forkResults = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (Path jfrFile : jfrFiles) {
            try {
                forkResults.add(extractFork(jfrFile));
            } catch (IOException e) {
                warnings.add("Failed to read JFR file " + jfrFile.getFileName() + ": " + e.getMessage());
            }
        }

        if (forkResults.isEmpty()) {
            return null;
        }

        JfrSummary.JfrSummaryBuilder summaryBuilder = aggregate(forkResults, benchmarkId, runId);
        if (!warnings.isEmpty()) {
            summaryBuilder.warnings(warnings);
        }

        JfrSummary summary = summaryBuilder.build();
        JsonWriter.write(benchDir.resolve("jfr-summary.json"), summary);
        return summary;
    }

    private ForkData extractFork(Path jfrFile) throws IOException {
        ForkData data = new ForkData();
        try (RecordingFile rf = new RecordingFile(jfrFile)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent event = rf.readEvent();
                String eventName = event.getEventType().getName();
                switch (eventName) {
                    case "jdk.GarbageCollection" -> handleGcEvent(event, data);
                    case "jdk.GCHeapSummary" -> handleHeapSummary(event, data);
                    case "jdk.SafepointBegin" -> handleSafepoint(event, data);
                    case "jdk.ObjectAllocationOutsideTLAB" -> data.largeAllocations++;
                    case "jdk.ZAllocationStall" -> data.allocationStalls++;
                    case "jdk.InitialSystemProperty" -> handleSystemProperty(event, data);
                    default -> { /* ignore other events */ }
                }
            }
        }
        return data;
    }

    private void handleGcEvent(RecordedEvent event, ForkData data) {
        data.gcCount++;
        Duration duration = event.getDuration("duration");
        double pauseMs = duration.toNanos() / 1_000_000.0;
        data.totalPauseMs += pauseMs;
        data.longestPauseMs = Math.max(data.longestPauseMs, pauseMs);
    }

    private void handleHeapSummary(RecordedEvent event, ForkData data) {
        try {
            long heapUsed = event.getLong("heapUsed");
            data.peakHeapUsedBytes = Math.max(data.peakHeapUsedBytes, heapUsed);
        } catch (Exception ignored) {
            // Field may not be present in all JDK versions
        }
    }

    private void handleSafepoint(RecordedEvent event, ForkData data) {
        data.safepointCount++;
        try {
            Duration ttsp = event.getDuration("totalTimeToSafepoint");
            if (ttsp != null) {
                double ttspMs = ttsp.toNanos() / 1_000_000.0;
                data.ttspValues.add(ttspMs);
            }
        } catch (Exception ignored) {
            // TTSP field may vary by JDK version
        }
    }

    private void handleSystemProperty(RecordedEvent event, ForkData data) {
        try {
            String key = event.getString("key");
            String value = event.getString("value");
            if (key != null && value != null) {
                data.systemProperties.put(key, value);
            }
        } catch (Exception ignored) {
            // Gracefully handle missing fields
        }
    }

    private JfrSummary.JfrSummaryBuilder aggregate(List<ForkData> forks,
                                                          String benchmarkId, String runId) {
        JfrSummary.JfrSummaryBuilder summaryBuilder = JfrSummary.builder()
                .benchmarkId(benchmarkId)
                .runId(runId)
                .forkCount(forks.size())
                .aggregation("across-all-forks");

        // GC event stats: SUM counts/totals, MAX for longest
        GcEventStats.GcEventStatsBuilder gcEventStatsBuilder = GcEventStats.builder();
        int totalCollections = 0;
        double totalPauseMs = 0.0;
        double longestPauseMs = 0.0;

        for (ForkData fork : forks) {
            totalCollections += fork.gcCount;
            totalPauseMs = totalPauseMs + fork.totalPauseMs;
            longestPauseMs = Math.max(longestPauseMs, fork.longestPauseMs);
        }


        if (totalCollections > 0) {

            gcEventStatsBuilder.totalCollections(totalCollections)
                    .totalPauseMs(totalPauseMs)
                    .longestPauseMs(longestPauseMs);

            summaryBuilder.gcEvents(gcEventStatsBuilder.build());
        }

        // Large allocations & stalls: SUM
        summaryBuilder.largeObjectAllocations(forks.stream().mapToInt(f -> f.largeAllocations).sum());
        summaryBuilder.allocationStalls(forks.stream().mapToInt(f -> f.allocationStalls).sum());

        // Safepoint stats: aggregate TTSP values from all forks
        List<Double> allTtsp = new ArrayList<>();
        int totalSafepointEvents = 0;
        for (ForkData fork : forks) {
            totalSafepointEvents += fork.safepointCount;
            allTtsp.addAll(fork.ttspValues);
        }
        if (!allTtsp.isEmpty()) {
            SafepointJfrStats safepointStats = SafepointJfrStats.builder()
                    .events(totalSafepointEvents)
                    .ttspMeanMs(allTtsp.stream().mapToDouble(Double::doubleValue).average().orElse(0.0))
                    .ttspMaxMs(allTtsp.stream().mapToDouble(Double::doubleValue).max().orElse(0.0))
                    .build();
            summaryBuilder.safepoint(safepointStats);
        }

        // JVM ergonomics: take from FIRST fork
        ForkData firstFork = forks.get(0);
        summaryBuilder.jvmErgonomics(extractErgonomics(firstFork.systemProperties));

        return summaryBuilder;
    }

    private JvmErgonomics extractErgonomics(Map<String, String> props) {
        if (props.isEmpty()) {
            return null;
        }

        JvmErgonomics.JvmErgonomicsBuilder jvmErgonomicsBuilder = JvmErgonomics.builder();

        String gcAlgorithm = detectGcAlgorithm(props);
        if (gcAlgorithm != null) {
            jvmErgonomicsBuilder.gcAlgorithm(gcAlgorithm);
        }

        // Extract numeric ergonomics
        jvmErgonomicsBuilder.parallelGcThreads(parseIntProperty(props, "ParallelGCThreads"));
        jvmErgonomicsBuilder.concGcThreads(parseIntProperty(props, "ConcGCThreads"));
        jvmErgonomicsBuilder.g1HeapRegionSize(parseIntProperty(props, "G1HeapRegionSize"));
        jvmErgonomicsBuilder.softRefLruPolicyMsPerMb(parseIntProperty(props, "SoftRefLRUPolicyMSPerMB"));

        // Collect effective flags
        List<String> flags = new ArrayList<>();
        for (Map.Entry<String, String> entry : props.entrySet()) {
            if (GC_FLAG_PREFIXES.contains(entry.getKey())) {
                flags.add("-XX:" + entry.getKey() + "=" + entry.getValue());
            }
        }
        if (!flags.isEmpty()) {
            Collections.sort(flags);
            jvmErgonomicsBuilder.effectiveFlags(flags);
        }

        return jvmErgonomicsBuilder.build();
    }

    private String detectGcAlgorithm(Map<String, String> props) {
        String gcAlgorithm = null;
        for (Map.Entry<String, String> entry : props.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if ("true".equals(value)) {
                gcAlgorithm = switch (key) {
                    case "UseG1GC" -> "G1";
                    case "UseZGC" -> "ZGC";
                    case "UseParallelGC" -> "Parallel";
                    case "UseSerialGC" -> "Serial";
                    case "UseShenandoahGC" -> "Shenandoah";
                    case "UseEpsilonGC" -> "Epsilon";
                    default -> null;
                };
            }
        }
        return gcAlgorithm;
    }

    private Integer parseIntProperty(Map<String, String> props, String key) {
        String value = props.get(key);
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static class ForkData {
        int gcCount;
        double totalPauseMs;
        double longestPauseMs;
        long peakHeapUsedBytes;
        int largeAllocations;
        int allocationStalls;
        int safepointCount;
        List<Double> ttspValues = new ArrayList<>();
        Map<String, String> systemProperties = new LinkedHashMap<>();
    }
}
