package net.szumigaj.gcobs.cli.executor;

import net.szumigaj.gcobs.cli.threshold.ThresholdResult;

import java.time.Duration;

public record BenchmarkResult(String benchmarkId, int exitCode, Duration duration, ThresholdResult thresholdResult) {

    public boolean isSuccess() {
        return exitCode == 0;
    }

    public boolean isFailure() {
        return !isSuccess();
    }

}
