package net.szumigaj.gcobs.cli.model.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public record ThresholdsConfig(Double gcOverheadMaxPct, Double gcPauseP95MaxMs, Double gcPauseP99MaxMs,
                               Double gcPauseMaxMs, Integer gcFullCountMax, Integer gcAllocationStallsMax,
                               Integer heapPeakUsedMaxMb, Double jmhScoreRegressionMaxPct, Double gcCpuMaxPct,
                               Integer gcSystemGcCountMax, Integer metaspaceUsedMaxMb) {
}
