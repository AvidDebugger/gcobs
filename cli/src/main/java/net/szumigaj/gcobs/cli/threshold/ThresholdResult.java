package net.szumigaj.gcobs.cli.threshold;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.util.List;


@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ThresholdResult(ThresholdStatus status, List<Breach> breaches, List<PassingEntry> passing,
                              List<SkippedEntry> skipped) {

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Breach(String field, double threshold, double actual, String message) {
    }

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
        public record PassingEntry(String field, double threshold, double actual) {
    }

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
        public record SkippedEntry(String field, String reason) {
    }

    public enum ThresholdStatus {
        PASS, FAIL, SKIPPED
    }
}
