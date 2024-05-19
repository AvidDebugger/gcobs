package net.szumigaj.gcobs.cli.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.util.List;
import java.util.Map;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AggregationResult(String specName, int runCount, List<String> runs,
                                Map<String, BenchmarkAggregation> benchmarks) {

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
        public record BenchmarkAggregation(MetricStats jmhScore, MetricStats gcOverheadPct, MetricStats gcPauseP99Ms,
                                           MetricStats gcCountFull) {
    }

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
        public record MetricStats(double mean, double stddev, double min, double max) {
            public static MetricStats compute(List<Double> values) {
                if (values == null || values.isEmpty()) {
                    return null;
                }
                double sum = 0;
                double min = Double.MAX_VALUE;
                double max = Double.MIN_VALUE;
                for (double v : values) {
                    sum += v;
                    if (v < min) min = v;
                    if (v > max) max = v;
                }
                double mean = sum / values.size();
                double variance = 0;
                for (double v : values) {
                    variance += (v - mean) * (v - mean);
                }
                double stddev = values.size() > 1
                        ? Math.sqrt(variance / (values.size() - 1))
                        : 0.0;
                return new MetricStats(mean, stddev, min, max);
            }
        }
}
