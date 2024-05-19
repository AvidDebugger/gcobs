package net.szumigaj.gcobs.cli.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AggregationResultTest {

    @Test
    void compute_returnsNull_whenValuesIsNull() {
        assertThat(AggregationResult.MetricStats.compute(null)).isNull();
    }

    @Test
    void compute_returnsNull_whenValuesIsEmpty() {
        assertThat(AggregationResult.MetricStats.compute(List.of())).isNull();
    }

    @Test
    void compute_singleValue_hasZeroStddevAndEqualMinMax() {
        AggregationResult.MetricStats stats = AggregationResult.MetricStats.compute(List.of(7.0));

        assertThat(stats).isNotNull();
        assertThat(stats.mean()).isEqualTo(7.0, within(1e-9));
        assertThat(stats.stddev()).isEqualTo(0.0, within(1e-9));
        assertThat(stats.min()).isEqualTo(7.0, within(1e-9));
        assertThat(stats.max()).isEqualTo(7.0, within(1e-9));
    }

    @Test
    void compute_multipleValues_correctMeanMinMax() {
        AggregationResult.MetricStats stats = AggregationResult.MetricStats.compute(List.of(1.0, 3.0, 5.0));

        assertThat(stats).isNotNull();
        assertThat(stats.mean()).isEqualTo(3.0, within(1e-9));
        assertThat(stats.min()).isEqualTo(1.0, within(1e-9));
        assertThat(stats.max()).isEqualTo(5.0, within(1e-9));
    }

    @Test
    void compute_usesSampleStddev_notPopulation() {
        // population stddev of [2.0, 4.0] = 1.0
        // sample stddev of [2.0, 4.0] = sqrt(2) ≈ 1.4142
        AggregationResult.MetricStats stats = AggregationResult.MetricStats.compute(List.of(2.0, 4.0));

        assertThat(stats).isNotNull();
        assertThat(stats.mean()).isEqualTo(3.0, within(1e-9));
        assertThat(stats.stddev()).isCloseTo(Math.sqrt(2.0), within(1e-9));
    }

    @Test
    void compute_twoIdenticalValues_zeroStddev() {
        AggregationResult.MetricStats stats = AggregationResult.MetricStats.compute(List.of(5.0, 5.0));

        assertThat(stats).isNotNull();
        assertThat(stats.stddev()).isEqualTo(0.0, within(1e-9));
    }
}
