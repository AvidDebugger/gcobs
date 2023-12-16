package net.szumigaj.gcobs.cli.artifact;

import net.szumigaj.gcobs.cli.model.EnvironmentInfo;
import net.szumigaj.gcobs.cli.model.GcSummary;
import net.szumigaj.gcobs.cli.model.JfrSummary;
import net.szumigaj.gcobs.cli.model.SourceConfig;
import net.szumigaj.gcobs.cli.spec.EffectiveBenchmarkConfig;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Bundles all data needed to write benchmark-summary.json.
 */
public record BenchmarkContext(String benchmarkId, String runId, String status, Instant startedAt, Instant finishedAt,
                               long durationMs, EffectiveBenchmarkConfig config, SourceConfig source,
                               GcSummary gcSummary, JfrSummary jfrSummary, EnvironmentInfo environment, Path benchDir) {
}
