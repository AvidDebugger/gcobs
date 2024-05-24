package net.szumigaj.gcobs.common.mixed;

import lombok.Builder;
import net.szumigaj.gcobs.common.allocation.BatchKernel;
import net.szumigaj.gcobs.common.service.http.FakeHttpClientKernel;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Combines BatchKernel allocation patterns with FakeHttpClientKernel request simulation.
 * Represents a realistic mixed workload: an application that both processes
 * batch data and serves concurrent requests.
 *
 * Pattern: Alternates between batch allocation bursts and simulated request handling
 *   - Every {@code burstFrequency}-th iteration: delegate to BatchKernel (heavy allocation, old-gen retention)
 *   - Between bursts: delegate to FakeHttpClientKernel (connection pool, ephemeral request/response, retry path)
 *
 * GC paths stressed:
 *   - Mixed young/old generation pressure from two independent old-gen footprints:
 *     BatchKernel survivors (deque of retained byte arrays) and FakeHttpClientKernel connection pool
 *   - GC must balance throughput (batch) vs latency (service)
 *   - Write barrier overhead during burst phases
 *   - Concurrent marking/evacuation during service phases
 *
 * Key observations:
 *   - ZGC maintains low P99 latency during burst-to-service transitions
 *   - G1GC may show latency spikes when transitioning from burst to service
 *   - Full GC risk under sustained batch pressure with limited heap
 */
public class MixedKernel {

    private final BatchKernel batchKernel;
    private final FakeHttpClientKernel serviceKernel;
    private final int burstFrequency;
    private final int serviceRequestCount;
    private final int sleepMs;

    private int iterationCount = 0;

    @Builder
    private MixedKernel(int payloadBytes, int batchSize, int sleepMs,
                        int burstFrequency, int retentionRatio, int survivorsCap,
                        int poolSize, int retryRateModulo) {
        this.sleepMs = sleepMs;
        this.burstFrequency = burstFrequency;
        this.serviceRequestCount = batchSize / 4;
        this.batchKernel = BatchKernel.builder()
                .payloadBytes(payloadBytes)
                .batchSize(batchSize)
                .retentionRatio(retentionRatio)
                .survivorsCap(survivorsCap)
                .build();
        this.serviceKernel = FakeHttpClientKernel.builder()
                .payloadBytes(payloadBytes)
                .poolSize(poolSize)
                .retryRateModulo(retryRateModulo)
                .build();
    }

    public static class MixedKernelBuilder {
        private int batchSize = 80;
        private int sleepMs = 0;
        private int burstFrequency = 5;
        private int retentionRatio = 16;
        private int survivorsCap = 2048;
        private int poolSize = 64;
        private int retryRateModulo = 51;
    }

    public long run(Blackhole blackhole) {
        long checksum = 0;
        iterationCount++;

        if (iterationCount % burstFrequency == 0) {
            checksum += batchKernel.run(blackhole);
        } else {
            for (int i = 0; i < serviceRequestCount; i++) {
                checksum += serviceKernel.handleRequest(blackhole);
            }
        }

        if (sleepMs > 0) {
            try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        return checksum;
    }

    public int getSurvivorsSize() {
        return batchKernel.getSurvivorsSize();
    }

    public int getIterationCount() {
        return iterationCount;
    }
}
