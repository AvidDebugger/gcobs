package net.szumigaj.gcobs.cli.artifact;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import net.szumigaj.gcobs.cli.compare.CompareResult;
import net.szumigaj.gcobs.cli.model.env.EnvironmentInfo;

import java.util.List;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunManifestModel(String runId, String createdAt, Tool tool, Spec spec, Profile profile,
                               List<BenchmarkEntry> benchmarks, List<CompareResult> comparisons,
                               ThresholdSummary thresholdSummary, Execution execution, List<String> warnings,
                               EnvironmentInfo environment) {

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Tool(String name, String version) {
    }

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Spec(String path, String sha256, String name) {
    }

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Profile(String mode, boolean comparable) {
    }

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
        public record BenchmarkEntry(String id, String status, long durationMs, Boolean thresholdsPassed, Double jmhScore,
                                     String jmhScoreUnit, Double jmhScoreError, Double gcOverheadPct, Double gcPauseP99Ms,
                                     Integer gcCountFull, String summaryPath) {
    }

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Execution(int exitCode, long durationMs, String status, int benchmarksTotal, int benchmarksSuccess,
                                int benchmarksFailed) {
    }

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
        public record ThresholdSummary(boolean allPassed, List<RunManifestModel.ThresholdBreach> breaches) {
    }

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
        public record ThresholdBreach(String benchmarkId, String field, double threshold, double actual, String message) {
    }
}
