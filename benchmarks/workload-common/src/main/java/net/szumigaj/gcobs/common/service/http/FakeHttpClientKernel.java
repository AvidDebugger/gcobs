package net.szumigaj.gcobs.common.service.http;

import lombok.Builder;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Simulates HTTP client making requests with connection pooling.
 * Allocation pattern: build request (ephemeral) -> pick pooled connection -> parse response (ephemeral).
 * Retry injection: configurable rate triggers retry with extra allocation.
 *
 * GC path stressed: Young-gen heavy with steady old-gen footprint from connection pool.
 *   - G1GC: Evacuation Pause (Young), Mixed GC from connection pool promotion
 *   - ZGC: Minor GC (Young generation), Major from pool objects
 *   - ParallelGC: Minor collection, Major from pool pressure
 */
public class FakeHttpClientKernel {

    private final int payloadBytes;
    private final int sleepMs;
    private final int poolSize;
    private final int retryRateModulo;

    private final byte[][] connectionPool;
    private int requestCount;

    @Builder
    private FakeHttpClientKernel(int payloadBytes, int sleepMs, int poolSize, int retryRateModulo) {
        this.payloadBytes = payloadBytes;
        this.sleepMs = sleepMs;
        this.poolSize = poolSize;
        this.retryRateModulo = retryRateModulo;
        this.connectionPool = new byte[poolSize][];
        for (int i = 0; i < poolSize; i++) {
            connectionPool[i] = new byte[payloadBytes / 4];
        }
    }

    public static class FakeHttpClientKernelBuilder {
        private int sleepMs = 0;
        private int poolSize = 64;
        private int retryRateModulo = 51;
    }

    public long handleRequest(Blackhole blackhole) {
        requestCount++;

        // 1. Build request (serialized body - ephemeral)
        byte[] requestBody = new byte[payloadBytes];
        requestBody[0] = (byte)(requestCount & 0xFF);
        blackhole.consume(requestBody);

        // 2. Pick a connection from the pool
        byte[] connection = connectionPool[requestCount % poolSize];

        // 3. Receive response (ephemeral - parsed and discarded)
        byte[] responseBody = new byte[payloadBytes];
        responseBody[0] = connection[0];
        blackhole.consume(responseBody);

        // 4. Retry path: extra allocation for retry attempt
        if (requestCount % retryRateModulo == 0) {
            byte[] retryBody = new byte[payloadBytes];
            retryBody[0] = (byte)((requestCount + 1) & 0xFF);
            blackhole.consume(retryBody);
            return requestBody[0] + retryBody[0];
        }

        // 5. Simulate network latency
        if (sleepMs > 0) {
            try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        return requestBody[0] + responseBody[0];
    }
}
