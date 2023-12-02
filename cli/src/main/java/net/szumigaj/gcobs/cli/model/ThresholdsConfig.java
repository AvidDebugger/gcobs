package net.szumigaj.gcobs.cli.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ThresholdsConfig(Double gcOverheadMaxPct, Double gcPauseP95MaxMs, Double gcPauseP99MaxMs,
                               Double gcPauseMaxMs, Integer gcFullCountMax, Integer gcAllocationStallsMax,
                               Integer heapPeakUsedMaxMb, Double jmhScoreRegressionMaxPct, Double gcCpuMaxPct,
                               Integer gcSystemGcCountMax, Integer metaspaceUsedMaxMb) {
}
