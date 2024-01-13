package net.szumigaj.gcobs.cli.compare;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.util.List;


@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompareResultModel(String comparisonId, String description, String producedAt, RunRef base,
                                 RunRef candidate, CompareResult.EnvironmentMatch environmentMatch,
                                 DecisionContext decisionContext, ComparisonVerdict verdict,
                                 List<CompareResult.MetricDelta> metrics, List<String> warnings) {

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunRef(String runId, String benchmarkId, List<String> jvmArgs) {
    }

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DecisionContext(String thresholdPolicy, List<String> missingThresholds, boolean confidenceAware,
                                  String confidenceMethod, String confidenceLevel, boolean requireEnvMatch,
                                  boolean suppressedByEnvMismatch, List<String> notes) {
    }
}
