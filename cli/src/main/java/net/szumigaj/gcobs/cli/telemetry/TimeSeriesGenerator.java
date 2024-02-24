package net.szumigaj.gcobs.cli.telemetry;

import jakarta.inject.Singleton;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Singleton
public class TimeSeriesGenerator {

    // JMH iteration output pattern:
    // "Iteration   1: 0.015 ms/op"  or  "# Warmup Iteration   1: 0.015 ms/op"
    private static final Pattern WARMUP_PATTERN = Pattern.compile(
            "#\\s*Warmup Iteration\\s+(\\d+):\\s+([\\d.]+(?:E[+-]?\\d+)?)\\s+(\\S+)");
    private static final Pattern MEASUREMENT_PATTERN = Pattern.compile(
            "Iteration\\s+(\\d+):\\s+([\\d.]+(?:E[+-]?\\d+)?)\\s+(\\S+)");

    // GC log pause pattern (unified logging):
    // [0.123s][info][gc] GC(0) Pause Young ... 10M->5M(256M) 1.234ms
    private static final Pattern GC_PAUSE_PATTERN = Pattern.compile(
            "\\[(\\d+\\.\\d+)s\\].*Pause\\s+\\S+.*?(\\d+)M->(\\d+)M\\((\\d+)M\\)\\s+(\\d+\\.\\d+)ms");

    /**
     * Generates metrics-timeseries.jsonl from JMH stdout and GC logs.
     */
    public void generate(Path benchDir) throws IOException {
        Path stdoutLog = benchDir.resolve("jmh.stdout.log");
        if (!Files.exists(stdoutLog)) {
            return;
        }

        List<IterationEntry> iterations = parseIterations(stdoutLog);
        if (iterations.isEmpty()) {
            return;
        }

        // Parse GC events for correlation
        Path gcLog = benchDir.resolve("gc.log");
        List<GcEvent> gcEvents = Files.exists(gcLog) ? parseGcEvents(gcLog) : List.of();

        // Correlate GC events with iterations (best-effort by sequence)
        // Since we can't reliably map timestamps across processes, we distribute
        // GC events proportionally across measurement iterations
        int measurementCount = (int) iterations.stream()
                .filter(it -> "measurement".equals(it.phase)).count();
        int gcPerIteration = measurementCount > 0 ? gcEvents.size() / measurementCount : 0;

        Path output = benchDir.resolve("metrics-timeseries.jsonl");
        try (BufferedWriter writer = Files.newBufferedWriter(output)) {
            int gcIndex = 0;
            for (IterationEntry it : iterations) {
                int gcPauseCount = 0;
                double gcPauseTotalMs = 0;
                double heapBeforeMb = 0;
                double heapAfterMb = 0;

                // Assign GC events to measurement iterations
                if ("measurement".equals(it.phase) && gcPerIteration > 0 && gcIndex < gcEvents.size()) {
                    int end = Math.min(gcIndex + gcPerIteration, gcEvents.size());
                    for (int i = gcIndex; i < end; i++) {
                        GcEvent gc = gcEvents.get(i);
                        gcPauseCount++;
                        gcPauseTotalMs += gc.durationMs;
                        heapBeforeMb = Math.max(heapBeforeMb, gc.heapBeforeMb);
                        heapAfterMb = gc.heapAfterMb;
                    }
                    gcIndex = end;
                }

                String json = String.format(
                        "{\"iteration\":%d,\"phase\":\"%s\",\"jmhScore\":%.6f" +
                                ",\"gcPauseCount\":%d,\"gcPauseTotalMs\":%.1f" +
                                ",\"heapBeforeMb\":%.0f,\"heapAfterMb\":%.0f}",
                        it.iteration, it.phase, it.score,
                        gcPauseCount, gcPauseTotalMs,
                        heapBeforeMb, heapAfterMb);
                writer.write(json);
                writer.newLine();
            }
        }
    }

    private List<IterationEntry> parseIterations(Path stdoutLog) throws IOException {
        List<IterationEntry> entries = new ArrayList<>();
        List<String> lines = Files.readAllLines(stdoutLog);

        for (String line : lines) {
            Matcher warmup = WARMUP_PATTERN.matcher(line);
            if (warmup.find()) {
                entries.add(new IterationEntry(
                        Integer.parseInt(warmup.group(1)),
                        "warmup",
                        Double.parseDouble(warmup.group(2))));
                continue;
            }

            Matcher measurement = MEASUREMENT_PATTERN.matcher(line);
            if (measurement.find()) {
                entries.add(new IterationEntry(
                        Integer.parseInt(measurement.group(1)),
                        "measurement",
                        Double.parseDouble(measurement.group(2))));
            }
        }

        return entries;
    }

    private List<GcEvent> parseGcEvents(Path gcLog) throws IOException {
        List<GcEvent> events = new ArrayList<>();
        List<String> lines = Files.readAllLines(gcLog);

        for (String line : lines) {
            if (line.startsWith("# ===")) continue; // Fork separator
            Matcher m = GC_PAUSE_PATTERN.matcher(line);
            if (m.find()) {
                events.add(new GcEvent(
                        Double.parseDouble(m.group(1)),
                        Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)),
                        Integer.parseInt(m.group(4)),
                        Double.parseDouble(m.group(5))));
            }
        }

        return events;
    }

    private record IterationEntry(int iteration, String phase, double score) {}

    private record GcEvent(double uptimeSec, int heapBeforeMb, int heapAfterMb,
                            int capacityMb, double durationMs) {}
}
