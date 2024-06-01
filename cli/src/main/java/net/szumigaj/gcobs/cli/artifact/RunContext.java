package net.szumigaj.gcobs.cli.artifact;

import net.szumigaj.gcobs.cli.executor.BenchmarkResult;
import net.szumigaj.gcobs.cli.model.config.BenchmarkRunSpec;
import net.szumigaj.gcobs.cli.model.env.EnvironmentInfo;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Bundles all data needed to write run.json and report.md.
 */
public record RunContext(
        String runId,
        Instant startedAt,
        Instant finishedAt,
        Path specPath,
        BenchmarkRunSpec spec,
        List<BenchmarkResult> results,
        EnvironmentInfo environment,
        Path runDir,
        int exitCode
) {}
