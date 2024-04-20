package net.szumigaj.gcobs.benchmark.reference;

import net.szumigaj.gcobs.common.reference.ReferenceKernel;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(value = 3, jvmArgsAppend = {})
public class ReferenceKernelBenchmark {

    @Param("10")
    int iterations;

    @Param("50")
    int batchSize;

    @Param("1024")
    int payloadBytes;

    @Param("0")
    int sleepMs;

    private ReferenceKernel kernel;

    @Setup(Level.Trial)
    public void setup() {
        kernel = ReferenceKernel.builder()
                .batchSize(batchSize)
                .payloadBytes(payloadBytes)
                .sleepMs(sleepMs)
                .build();
    }

    @Benchmark
    public long referenceKernelChecksum(Blackhole blackhole) {
        long total = 0;
        for (int i = 0; i < iterations; i++) {
            total += kernel.run(blackhole);
        }
        return total;
    }
}
