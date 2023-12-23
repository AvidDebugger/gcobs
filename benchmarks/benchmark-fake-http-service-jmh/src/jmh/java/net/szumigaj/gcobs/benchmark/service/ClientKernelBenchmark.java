package net.szumigaj.gcobs.benchmark.service;

import net.szumigaj.gcobs.common.service.http.FakeHttpClientKernel;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(value = 3, jvmArgsAppend = {})
public class ClientKernelBenchmark {

    @Param("320") int payloadBytes;
    @Param("0") int sleepMs;
    @Param("64") int poolSize;
    @Param("51") int retryRateModulo;

    private FakeHttpClientKernel kernel;

    @Setup(Level.Trial)
    public void setup() {
        kernel = FakeHttpClientKernel.builder().payloadBytes(payloadBytes).sleepMs(sleepMs).poolSize(poolSize).retryRateModulo(retryRateModulo).build();
    }

    @Benchmark
    public long handleRequest(Blackhole blackhole) {
        return kernel.handleRequest(blackhole);
    }
}
