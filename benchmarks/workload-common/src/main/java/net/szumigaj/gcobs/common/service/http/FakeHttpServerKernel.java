package net.szumigaj.gcobs.common.service.http;

import lombok.Builder;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Simulates HTTP server request handling with buffer pooling.
 * Allocation pattern: parse request body (ephemeral) -> process -> write into reusable response buffer.
 * Error injection: configurable rate triggers extra allocations (error payload + log entry).
 *
 * GC path stressed: Mostly young-gen with small old-gen footprint.
 *   - G1GC: Evacuation Pause (Young), occasional Mixed GC from responseBuffer promotion
 *   - ZGC: Minor GC (Young generation)
 *   - ParallelGC: Minor collection
 */
public class FakeHttpServerKernel {

    private final int payloadBytes;
    private final int sleepMs;
    private final int errorRateModulo;

    private final byte[] responseBuffer;
    private int requestCount;

    @Builder
    private FakeHttpServerKernel(int payloadBytes, int sleepMs, int errorRateModulo) {
        this.payloadBytes = payloadBytes;
        this.sleepMs = sleepMs;
        this.errorRateModulo = errorRateModulo;
        this.responseBuffer = new byte[payloadBytes];
    }

    public static class FakeHttpServerKernelBuilder {
        private int sleepMs = 0;
        private int errorRateModulo = 101;
    }

    public long handleRequest(Blackhole blackhole) {
        requestCount++;

        // 1. Parse incoming request body (ephemeral - dies after method)
        byte[] requestBody = new byte[payloadBytes / 2];
        requestBody[0] = (byte)(requestCount & 0xFF);
        blackhole.consume(requestBody);

        // 2. Error path: extra allocations for error payload + log entry
        if (requestCount % errorRateModulo == 0) {
            byte[] errorPayload = new byte[payloadBytes];
            System.arraycopy(requestBody, 0, errorPayload, 0, requestBody.length);
            byte[] logEntry = new byte[512];
            logEntry[0] = (byte)(requestCount & 0xFF);
            blackhole.consume(errorPayload);
            blackhole.consume(logEntry);
            return requestBody[0] + errorPayload[0] + logEntry[0];
        }

        // 3. Write response into reused buffer (server-side buffer pooling)
        System.arraycopy(requestBody, 0, responseBuffer, 0, requestBody.length);

        // 4. Simulate I/O latency
        if (sleepMs > 0) {
            try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        return responseBuffer[0];
    }
}
