package net.szumigaj.gcobs.benchmark.mixed;

import net.szumigaj.gcobs.common.mixed.MixedKernel;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(value = 3, jvmArgsAppend = {})
public class MixedKernelBenchmark {

    @Param("20") int iterations;
    @Param("100") int batchSize;
    @Param("320") int payloadBytes;
    @Param("0") int sleepMs;

    private MixedKernel kernel;

    @Setup(Level.Trial)
    public void setup() {
        kernel = MixedKernel.builder()
                .batchSize(batchSize)
                .payloadBytes(payloadBytes)
                .sleepMs(sleepMs)
                .build();
    }

    @Benchmark
    public long mixedKernelChecksum(Blackhole blackhole) {
        long total = 0;
        for (int i = 0; i < iterations; i++) {
            total += kernel.run(blackhole);
        }
        return total;
    }
}
