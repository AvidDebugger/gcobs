package net.szumigaj.gcobs.benchmark.ephemeral;

import net.szumigaj.gcobs.common.allocation.EphemeralKernel;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(value = 3, jvmArgsAppend = {})
public class EphemeralKernelBenchmark {

    @Param("10") int iterations;
    @Param("80") int batchSize;
    @Param("320") int payloadBytes;
    @Param("0") int sleepMs;

    private EphemeralKernel kernel;

    @Setup(Level.Trial)
    public void setup() {
        kernel = EphemeralKernel.builder().payloadBytes(payloadBytes).batchSize(batchSize).sleepMs(sleepMs).build();
    }

    @Benchmark
    public long ephemeralKernelChecksum(Blackhole blackhole) {
        long total = 0;
        for (int i = 0; i < iterations; i++) {
            total += kernel.run(blackhole);
        }
        return total;
    }
}
