package net.szumigaj.gcobs.common.allocation;

import lombok.Builder;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayDeque;

/**
 * Allocates objects with given retention ratio up to provided capacity.
 * Creates old-generation footprint and triggers promotion + major GC.
 *
 * GC path stressed: Young + Old generation.
 *   - G1GC: Young (Evacuation) + Mixed GC
 *   - ZGC: Young + Major collection
 *   - ParallelGC: Minor + Major collection
 *   - Full GC escalation under heap pressure
 */
public class BatchKernel {

    private final int payloadBytes;
    private final int batchSize;
    private final int sleepMs;
    private final int retentionRatio;
    private final int survivorsCapacity;

    private final ArrayDeque<byte[]> survivors;
    private int allocationCount;

    @Builder
    private BatchKernel(int payloadBytes, int batchSize, int sleepMs,
                        int retentionRatio, int survivorsCap) {
        this.payloadBytes = payloadBytes;
        this.batchSize = batchSize;
        this.sleepMs = sleepMs;
        this.retentionRatio = retentionRatio;
        this.survivorsCapacity = survivorsCap;
        this.survivors = new ArrayDeque<>(survivorsCap);
    }

    public static class BatchKernelBuilder {
        private int batchSize = 80;
        private int sleepMs = 0;
        private int retentionRatio = 16;
        private int survivorsCap = 2048;
    }

    public long run(Blackhole blackhole) {
        long checksum = 0;
        for (int i = 0; i < batchSize; i++) {
            byte[] payload = new byte[payloadBytes];
            payload[0] = (byte)(allocationCount & 0xFF);
            checksum += payload[0];
            blackhole.consume(payload);
            allocationCount++;

            if (allocationCount % retentionRatio == 0) {
                if (survivors.size() >= survivorsCapacity) {
                    survivors.poll();
                }
                survivors.offer(payload);
            }
        }
        if (sleepMs > 0) {
            try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return checksum;
    }

    public int getSurvivorsSize() {
        return survivors.size();
    }
}
