package net.szumigaj.gcobs.cli.compare;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.util.List;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompareResult(String pairId, String baseBenchmarkId, String candidateBenchmarkId, String description,
                            ComparisonVerdict verdict, List<MetricDelta> metrics, EnvironmentMatch environmentMatch) {

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MetricDelta(String name, Double baseValue, Double candidateValue, Double delta, Double deltaPct,
                              Status status, Double threshold, ThresholdType thresholdType, Direction direction,
                              Boolean diagnostic) {
        public enum Status {
            REGRESSION, IMPROVEMENT, OK, UNKNOWN
        }

        public enum ThresholdType {
            PERCENTAGE, ABSOLUTE
        }

        public enum Direction {
            LOWER_IS_BETTER, HIGHER_IS_BETTER
        }
    }

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EnvironmentMatch(boolean compatible, List<String> warnings) {
    }

}
