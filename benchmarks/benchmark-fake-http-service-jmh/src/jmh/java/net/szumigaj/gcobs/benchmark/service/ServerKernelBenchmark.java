package net.szumigaj.gcobs.benchmark.service;

import net.szumigaj.gcobs.common.service.http.FakeHttpServerKernel;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(value = 3, jvmArgsAppend = {})
public class ServerKernelBenchmark {

    @Param("320") int payloadBytes;
    @Param("0") int sleepMs;
    @Param("101") int errorRateModulo;

    private FakeHttpServerKernel kernel;

    @Setup(Level.Trial)
    public void setup() {
        kernel = FakeHttpServerKernel.builder().payloadBytes(payloadBytes).sleepMs(sleepMs).errorRateModulo(errorRateModulo).build();
    }

    @Benchmark
    public long handleRequest(Blackhole blackhole) {
        return kernel.handleRequest(blackhole);
    }
}
