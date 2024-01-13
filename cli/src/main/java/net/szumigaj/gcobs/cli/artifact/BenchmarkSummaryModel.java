package net.szumigaj.gcobs.cli.artifact;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import net.szumigaj.gcobs.cli.model.EnvironmentInfo;
import net.szumigaj.gcobs.cli.threshold.ThresholdResult;

import java.util.List;
import java.util.Map;


@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BenchmarkSummaryModel(String benchmarkId, String runId, String status, String startedAt,
                                    String finishedAt,
                                    long durationMs, Source source, Jmh jmh, JmhProfilers jmhProfilers, Jvm jvm,
                                    Map<String, String> params, String gcSummaryRef, String jfrSummaryRef,
                                    EnvironmentInfo environment, Artifacts artifacts, List<String> warnings, ThresholdResult thresholdResult) {

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Source(String type, String module, String path, String projectDir) {
    }

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Jmh(String includes, int warmupIterations, int measurementIterations, int forks, int threads,
                      Double score, Double scoreError, String scoreUnit, double[] scoreConfidenceInterval) {
    }

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JmhProfilers(List<String> requested, List<String> effective, List<String> failed) {
    }

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Jvm(List<String> argsRequested, List<String> argsEffective, String injectedGcLogFlag,
                      String injectedJfrFlag, List<String> jfrFiles) {
    }

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Artifacts(List<String> gcLogs, String gcLog, String gcSummary, String gcSummaryWarmup,
                            List<String> jfrFiles, String jfrSummary, String jmhResultsJson, String jmhResultsCsv,
                            String cmdlineTxt, String stdout, String stderr, String timeseries) {
    }

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
        public record ThresholdBreach(String benchmarkId, String field, double threshold, double actual, String message) {
    }
}
