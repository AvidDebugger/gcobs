package net.szumigaj.gcobs.benchmark.humongous;

import net.szumigaj.gcobs.common.allocation.HumongousKernel;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(value = 3, jvmArgsAppend = {})
public class HumongousKernelBenchmark {

    @Param("5") int iterations;
    @Param("5") int batchSize;
    @Param("1048576") int payloadBytes;
    @Param("0") int sleepMs;

    private HumongousKernel kernel;

    @Setup(Level.Trial)
    public void setup() {
        kernel = HumongousKernel
                .builder()
                .payloadBytes(payloadBytes)
                .batchSize(batchSize)
                .sleepMs(sleepMs)
                .build();
    }

    @Benchmark
    public long humongousKernelChecksum(Blackhole blackhole) {
        long total = 0;
        for (int i = 0; i < iterations; i++) {
            total += kernel.run(blackhole);
        }
        return total;
    }
}
