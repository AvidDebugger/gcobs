package net.szumigaj.gcobs.cli.executor;

import lombok.Builder;

import java.nio.file.Path;
import java.util.List;

@Builder
public record ExecutionOptions(Path projectRoot, Path specPath, String runId, Path runsDir, boolean noJfr,
                               List<String> benchmarkFilter, boolean dryRun, String profile, boolean strictMetrics) {
}
