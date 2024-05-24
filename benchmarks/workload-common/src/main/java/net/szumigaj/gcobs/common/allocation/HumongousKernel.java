package net.szumigaj.gcobs.common.allocation;

import lombok.Builder;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Allocates objects >= G1 humongous threshold (region_size/2).
 * Triggers humongous allocation path in G1GC.
 *
 * GC paths stressed:
 *   - G1GC: Humongous allocation -> direct old-gen -> potential Full GC
 *   - ZGC: Large allocation (no special humongous path)
 *   - ParallelGC: Direct old-gen allocation
 *
 * Key observations:
 *   - G1 causeBreakdown will show "G1 Humongous Allocation"
 *   - jfr-summary.json largeObjectAllocations will be high
 *   - gcCountFull may increase with small heaps
 */
public class HumongousKernel {

    private static final int MIN_HUMONGOUS_SIZE = 524288; // 512KB

    private final int payloadBytes;
    private final int batchSize;
    private final int sleepMs;

    private int allocationCount;

    @Builder
    private HumongousKernel(int payloadBytes, int batchSize, int sleepMs) {
        this.payloadBytes = payloadBytes;
        this.batchSize = batchSize;
        this.sleepMs = sleepMs;
    }

    public static class HumongousKernelBuilder {
        private int batchSize = 80;
        private int sleepMs = 0;
    }

    public long run(Blackhole blackhole) {
        long checksum = 0;
        int humongousSize = Math.max(payloadBytes, MIN_HUMONGOUS_SIZE);

        for (int i = 0; i < batchSize; i++) {
            byte[] payload = new byte[humongousSize];
            payload[0] = (byte)(allocationCount++ & 0xFF);
            checksum += payload[0];
            blackhole.consume(payload);
        }

        if (sleepMs > 0) {
            try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return checksum;
    }
}
