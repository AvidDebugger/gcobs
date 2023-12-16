package net.szumigaj.gcobs.common.allocation;

import lombok.Builder;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Allocates objects that immediately become garbage.
 * Zero retention - measures pure young-gen collection frequency.
 *
 * GC path stressed: Young-gen only.
 *   - G1GC: Evacuation Pause (Young) only
 *   - ZGC: Minor GC (Young generation) only
 *   - SerialGC/ParallelGC: Minor collection only
 */
public class EphemeralKernel {

    private final int payloadBytes;
    private final int batchSize;
    private final int sleepMs;

    @Builder
    private EphemeralKernel(int payloadBytes, int batchSize, int sleepMs) {
        this.payloadBytes = payloadBytes;
        this.batchSize = batchSize;
        this.sleepMs = sleepMs;
    }

    public static class EphemeralKernelBuilder {
        private int batchSize = 80;
        private int sleepMs = 0;
    }

    public long run(Blackhole blackhole) {
        long checksum = 0;
        for (int i = 0; i < batchSize; i++) {
            byte[] payload = new byte[payloadBytes];
            checksum += payload.length;
            blackhole.consume(payload);
        }
        if (sleepMs > 0) {
            try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return checksum;
    }
}
