package net.szumigaj.gcobs.cli.artifact;

import net.szumigaj.gcobs.cli.model.env.EnvironmentInfo;
import net.szumigaj.gcobs.cli.model.result.GcSummary;
import net.szumigaj.gcobs.cli.model.result.JfrSummary;
import net.szumigaj.gcobs.cli.model.config.SourceConfig;
import net.szumigaj.gcobs.cli.spec.EffectiveBenchmarkConfig;
import net.szumigaj.gcobs.cli.threshold.ThresholdResult;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Bundles all data needed to write benchmark-summary.json.
 */
public record BenchmarkContext(String benchmarkId, String runId, String status, Instant startedAt, Instant finishedAt,
                               long durationMs, EffectiveBenchmarkConfig config, SourceConfig source,
                               GcSummary gcSummary, JfrSummary jfrSummary, EnvironmentInfo environment, Path benchDir,
                               ThresholdResult thresholdResult, List<String> rigorWarnings) {
}
