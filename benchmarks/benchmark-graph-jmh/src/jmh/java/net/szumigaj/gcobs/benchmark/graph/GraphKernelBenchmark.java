package net.szumigaj.gcobs.benchmark.graph;

import net.szumigaj.gcobs.common.reference.GraphKernel;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * GraphKernel stresses GC marking by building a complex reference graph.
 * Memory requirements: ~500KB per iteration at default params (MAX_NODES=4096, 256 bytes/node).
 * Default heap: 256MB is sufficient for standard runs. Increase for larger graphs.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(value = 3, jvmArgsAppend = {"-Xms256m", "-Xmx256m"})
public class GraphKernelBenchmark {

    @Param("5") int iterations;
    @Param("50") int batchSize;
    @Param("256") int payloadBytes;
    @Param("0") int sleepMs;

    private GraphKernel kernel;

    @Setup(Level.Trial)
    public void setup() {
        kernel = GraphKernel.builder()
                .batchSize(batchSize)
                .payloadBytes(payloadBytes)
                .sleepMs(sleepMs)
                .build();
    }

    @Benchmark
    public long graphKernelChecksum(Blackhole blackhole) {
        long total = 0;
        for (int i = 0; i < iterations; i++) {
            total += kernel.run(blackhole);
        }
        return total;
    }
}
