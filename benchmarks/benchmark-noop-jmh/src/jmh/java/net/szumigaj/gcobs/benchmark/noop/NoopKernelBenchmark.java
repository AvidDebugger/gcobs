package net.szumigaj.gcobs.benchmark.noop;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(value = 3, jvmArgsAppend = {})
public class NoopKernelBenchmark {

    @Param("10") int iterations;
    @Param("80") int batchSize;
    @Param("320") int payloadBytes;
    @Param("0") int sleepMs;

    private KernelParams params;

    @Setup(Level.Trial)
    public void setup() {
        params = new KernelParams(batchSize, payloadBytes, sleepMs);
    }

    @Benchmark
    public long noopKernelChecksum(Blackhole blackhole) {
        long total = 0;
        for (int i = 0; i < iterations; i++) {
            total += NoopKernel.run(params, blackhole);
        }
        return total;
    }
}
