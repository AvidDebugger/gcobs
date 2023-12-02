package net.szumigaj.gcobs.cli.executor;

import java.time.Duration;

public record BenchmarkResult(String benchmarkId, int exitCode, Duration duration) {

    public boolean isSuccess() {
        return exitCode == 0;
    }

    public boolean isFailure() {
        return !isSuccess();
    }

}
