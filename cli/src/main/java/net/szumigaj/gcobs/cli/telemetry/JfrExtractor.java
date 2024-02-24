package net.szumigaj.gcobs.cli.telemetry;

import jakarta.inject.Singleton;
import jdk.jfr.consumer.RecordedObject;
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

    private static final int TOP_N_CLASSES = 10;

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
                    case "jdk.ObjectAllocationOutsideTLAB" -> handleOutsideTlab(event, data);
                    case "jdk.ObjectAllocationInNewTLAB" -> handleInNewTlab(event, data);
                    case "jdk.ZAllocationStall" -> data.allocationStalls++;
                    case "jdk.InitialSystemProperty" -> handleSystemProperty(event, data);
                    case "jdk.GCCPUTime" -> handleGcCpuTime(event, data);
                    case "jdk.CPULoad" -> handleCpuLoad(event, data);
                    case "jdk.MetaspaceSummary" -> handleMetaspaceSummary(event, data);
                    case "jdk.Compilation" -> handleCompilation(event, data);
                    case "jdk.JavaMonitorEnter" -> handleMonitorEnter(event, data);
                    case "jdk.ThreadPark" -> handleThreadPark(event, data);
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

    private static void handleOutsideTlab(RecordedEvent event, ForkData data) {
        data.outsideTlabEvents++;
        try {
            long allocSize = event.getLong("allocationSize");
            String className = extractClassName(event);
            data.allocationsByClass.merge(className,
                    new long[]{1, allocSize},
                    (a, b) -> new long[]{a[0] + b[0], a[1] + b[1]});
        } catch (Exception ignored) {
            // Gracefully handle missing fields
        }
    }

    private static void handleInNewTlab(RecordedEvent event, ForkData data) {
        data.inNewTlabEvents++;
        try {
            long tlabSize = event.getLong("tlabSize");
            String className = extractClassName(event);
            data.allocationsByClass.merge(className,
                    new long[]{1, tlabSize},
                    (a, b) -> new long[]{a[0] + b[0], a[1] + b[1]});
        } catch (Exception ignored) {
            // Gracefully handle missing fields
        }
    }

    private static void handleGcCpuTime(RecordedEvent event, ForkData data) {
        try {
            long userNs = event.getLong("userTime");
            long systemNs = event.getLong("systemTime");
            long realNs = event.getLong("realTime");
            data.gcCpuUserNs += userNs;
            data.gcCpuSystemNs += systemNs;
            data.gcCpuRealNs += realNs;
            data.hasGcCpuTime = true;
        } catch (Exception ignored) {
            // Event structure may vary
        }
    }

    private static void handleCpuLoad(RecordedEvent event, ForkData data) {
        try {
            float jvmUser = event.getFloat("jvmUser");
            if (jvmUser >= 0) {
                data.cpuLoadSamples.add((double) jvmUser);
            }
        } catch (Exception ignored) {
            // Field may not be present
        }
    }

    private static void handleMetaspaceSummary(RecordedEvent event, ForkData data) {
        try {
            RecordedObject metaspace = event.getValue("metaspace");
            if (metaspace != null) {
                long used = metaspace.getLong("used");
                long committed = metaspace.getLong("committed");
                long reserved = metaspace.getLong("reserved");
                double usedMb = used / (1024.0 * 1024.0);
                double committedMb = committed / (1024.0 * 1024.0);
                double reservedMb = reserved / (1024.0 * 1024.0);
                data.metaspaceUsedMaxMb = Math.max(data.metaspaceUsedMaxMb, usedMb);
                data.metaspaceCommittedMaxMb = Math.max(data.metaspaceCommittedMaxMb, committedMb);
                data.metaspaceReservedMb = Math.max(data.metaspaceReservedMb, reservedMb);
                data.hasMetaspace = true;
            }
        } catch (Exception ignored) {
            // MetaspaceSummary structure may vary by JDK version
        }
    }

    private static void handleCompilation(RecordedEvent event, ForkData data) {
        data.compilationCount++;
        try {
            Duration duration = event.getDuration("duration");
            double ms = duration.toNanos() / 1_000_000.0;
            data.compilationTotalMs += ms;
            data.compilationMaxMs = Math.max(data.compilationMaxMs, ms);

            boolean isOsr = event.getBoolean("isOsr");
            if (isOsr) {
                data.compilationOsrCount++;
            }
        } catch (Exception ignored) {
            // Gracefully handle missing fields
        }
    }

    private static void handleMonitorEnter(RecordedEvent event, ForkData data) {
        data.monitorEvents++;
        try {
            Duration duration = event.getDuration("duration");
            double ms = duration.toNanos() / 1_000_000.0;
            data.monitorBlockedTotalMs += ms;
            data.maxBlockedMs = Math.max(data.maxBlockedMs, ms);

            String className = "unknown";
            try {
                var monitorClass = event.getClass("monitorClass");
                if (monitorClass != null) {
                    className = monitorClass.getName();
                }
            } catch (Exception e) {
                // monitorClass field may not be available
            }
            data.monitorByClass.merge(className,
                    new double[]{1, ms},
                    (a, b) -> new double[]{a[0] + b[0], a[1] + b[1]});
        } catch (Exception ignored) {
            // Gracefully handle missing fields
        }
    }

    private static void handleThreadPark(RecordedEvent event, ForkData data) {
        data.parkEvents++;
        try {
            Duration duration = event.getDuration("duration");
            double ms = duration.toNanos() / 1_000_000.0;
            data.parkBlockedTotalMs += ms;
            data.maxBlockedMs = Math.max(data.maxBlockedMs, ms);
        } catch (Exception ignored) {
            // Gracefully handle missing fields
        }
    }

    private static String extractClassName(RecordedEvent event) {
        try {
            var objClass = event.getClass("objectClass");
            if (objClass != null) {
                return objClass.getName();
            }
        } catch (Exception ignored) {
            // objectClass field may not be available
        }
        return "unknown";
    }

    private JfrSummary.JfrSummaryBuilder aggregate(List<ForkData> forks,
                                                          String benchmarkId, String runId) {
        JfrSummary.JfrSummaryBuilder summaryBuilder = JfrSummary.builder()
                .benchmarkId(benchmarkId)
                .runId(runId)
                .forkCount(forks.size())
                .aggregation("across-all-forks");

        List<String> warnings = new ArrayList<>();

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
        summaryBuilder.largeObjectAllocations(forks.stream().mapToInt(f -> f.outsideTlabEvents).sum());
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

        summaryBuilder.gcCpuPct(computeGcCpuPct(forks, warnings));
        summaryBuilder.allocationProfile(aggregateAllocationProfile(forks));
        summaryBuilder.memoryPools(aggregateMemoryPools(forks));
        summaryBuilder.compilation(aggregateCompilation(forks));
        summaryBuilder.contention(aggregateContention(forks));

        if (!warnings.isEmpty()) {
            summaryBuilder.warnings(warnings);
        }

        return summaryBuilder;
    }

    private static Double computeGcCpuPct(List<ForkData> forks, List<String> warnings) {
        // Primary: jdk.GCCPUTime (JDK 20+)
        boolean anyHasGcCpuTime = forks.stream().anyMatch(f -> f.hasGcCpuTime);
        if (anyHasGcCpuTime) {
            long totalUserSystem = 0;
            long totalReal = 0;
            for (ForkData fork : forks) {
                totalUserSystem += fork.gcCpuUserNs + fork.gcCpuSystemNs;
                totalReal += fork.gcCpuRealNs;
            }
            if (totalReal > 0) {
                return (totalUserSystem / (double) totalReal) * 100.0;
            }
        }

        // Fallback: jdk.CPULoad average jvmUser (less accurate)
        List<Double> allSamples = forks.stream()
                .flatMap(f -> f.cpuLoadSamples.stream())
                .toList();
        if (!allSamples.isEmpty()) {
            warnings.add("gcCpuPct derived from jdk.CPULoad (jdk.GCCPUTime unavailable). Value is approximate.");
            return allSamples.stream().mapToDouble(Double::doubleValue).average().orElse(0.0) * 100.0;
        }

        return null;
    }

    private static AllocationProfile aggregateAllocationProfile(List<ForkData> forks) {
        int totalOutsideTlab = 0;
        int totalInNewTlab = 0;
        Map<String, long[]> mergedClasses = new HashMap<>();

        for (ForkData fork : forks) {
            totalOutsideTlab += fork.outsideTlabEvents;
            totalInNewTlab += fork.inNewTlabEvents;
            for (var entry : fork.allocationsByClass.entrySet()) {
                mergedClasses.merge(entry.getKey(), entry.getValue(),
                        (a, b) -> new long[]{a[0] + b[0], a[1] + b[1]});
            }
        }

        int totalEvents = totalOutsideTlab + totalInNewTlab;
        if (totalEvents == 0) {
            return null;
        }

        return AllocationProfile.builder()
                .totalEvents(totalEvents)
                .outsideTlabEvents(totalOutsideTlab)
                .inNewTlabEvents(totalInNewTlab)
                .topClassesByCount(mergedClasses.entrySet().stream()
                        .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                        .limit(TOP_N_CLASSES)
                        .map(e -> new AllocationClassEntry(e.getKey(), (int) e.getValue()[0], e.getValue()[1]))
                        .toList())
                .build();
    }

    private static MemoryPools aggregateMemoryPools(List<ForkData> forks) {
        boolean anyHasMetaspace = forks.stream().anyMatch(f -> f.hasMetaspace);
        if (!anyHasMetaspace) {
            return null;
        }


        double usedMax = 0, committedMax = 0, reservedMax = 0;
        for (ForkData fork : forks) {
            usedMax = Math.max(usedMax, fork.metaspaceUsedMaxMb);
            committedMax = Math.max(committedMax, fork.metaspaceCommittedMaxMb);
            reservedMax = Math.max(reservedMax, fork.metaspaceReservedMb);
        }
        MemoryPools.Metaspace metaspace = MemoryPools.Metaspace.builder()
                .usedMaxMb(usedMax > 0 ? usedMax : null)
                .committedMaxMb(committedMax > 0 ? committedMax : null)
                .reservedMb(reservedMax > 0 ? reservedMax : null)
                .build();

        return MemoryPools.builder()
                .metaspace(metaspace)
                .build();
    }

    private static Compilation aggregateCompilation(List<ForkData> forks) {
        int totalCount = 0;
        double totalMs = 0;
        double maxMs = 0;
        int osrCount = 0;

        for (ForkData fork : forks) {
            totalCount += fork.compilationCount;
            totalMs += fork.compilationTotalMs;
            maxMs = Math.max(maxMs, fork.compilationMaxMs);
            osrCount += fork.compilationOsrCount;
        }

        if (totalCount == 0) {
            return null;
        }

        return Compilation.builder()
                .count(totalCount)
                .totalMs(totalMs)
                .maxMs(maxMs)
                .osrCount(osrCount)
                .build();
    }

    private static Contention aggregateContention(List<ForkData> forks) {
        int monitorEvents = 0;
        int parkEvents = 0;
        double totalBlockedMs = 0;
        double maxBlockedMs = 0;
        Map<String, double[]> mergedMonitors = new HashMap<>();

        for (ForkData fork : forks) {
            monitorEvents += fork.monitorEvents;
            parkEvents += fork.parkEvents;
            totalBlockedMs += fork.monitorBlockedTotalMs + fork.parkBlockedTotalMs;
            maxBlockedMs = Math.max(maxBlockedMs, fork.maxBlockedMs);

            for (var entry : fork.monitorByClass.entrySet()) {
                mergedMonitors.merge(entry.getKey(), entry.getValue(),
                        (a, b) -> new double[]{a[0] + b[0], a[1] + b[1]});
            }
        }

        if (monitorEvents == 0 && parkEvents == 0) {
            return null;
        }

        return Contention.builder()
                .monitorEvents(monitorEvents)
                .parkEvents(parkEvents)
                .totalBlockedMs(totalBlockedMs)
                .maxBlockedMs(maxBlockedMs)
                .topMonitorClasses(mergedMonitors.entrySet().stream()
                        .sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]))
                        .limit(TOP_N_CLASSES)
                        .map(e -> new ContentionClassEntry(e.getKey(), (int) e.getValue()[0], e.getValue()[1]))
                        .toList())
                .build();
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

        // allocation profiling
        int outsideTlabEvents;
        int inNewTlabEvents;
        Map<String, long[]> allocationsByClass = new HashMap<>();

        // GC CPU time (JDK 20+)
        long gcCpuUserNs;
        long gcCpuSystemNs;
        long gcCpuRealNs;
        boolean hasGcCpuTime;

        // CPU load fallback
        List<Double> cpuLoadSamples = new ArrayList<>();

        // metaspace
        double metaspaceUsedMaxMb;
        double metaspaceCommittedMaxMb;
        double metaspaceReservedMb;
        boolean hasMetaspace;

        // compilation
        int compilationCount;
        double compilationTotalMs;
        double compilationMaxMs;
        int compilationOsrCount;

        // contention
        int monitorEvents;
        int parkEvents;
        double monitorBlockedTotalMs;
        double parkBlockedTotalMs;
        double maxBlockedMs;
        Map<String, double[]> monitorByClass = new HashMap<>();
    }
}
