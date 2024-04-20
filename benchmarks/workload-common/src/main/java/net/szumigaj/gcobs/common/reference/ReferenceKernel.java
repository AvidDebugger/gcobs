package net.szumigaj.gcobs.common.reference;

import lombok.Builder;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.ref.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates reference chains (Soft/Weak/Phantom) to stress GC reference processing.
 *
 * GC paths stressed:
 *   - Reference discovery during marking
 *   - Reference processing phase (serial in G1 before JDK 18)
 *   - ReferenceQueue draining
 *
 * Key observations:
 *   - High PhantomRef count -> long reference processing phase
 *   - SoftRef retained under low heap pressure, cleared under high pressure
 *   - GC pause increase correlates with reference count
 */
public class ReferenceKernel {

    private static final int MAX_REFS = 4096;

    private final ReferenceQueue<byte[]> refQueue = new ReferenceQueue<>();
    private final List<Reference<byte[]>> activeRefs = new ArrayList<>();

    private final int payloadBytes;
    private final int batchSize;
    private final int sleepMs;

    private int allocationCount = 0;

    @Builder
    private ReferenceKernel(int payloadBytes, int batchSize, int sleepMs) {
        this.payloadBytes = payloadBytes;
        this.batchSize = batchSize;
        this.sleepMs = sleepMs;
    }

    public long run(Blackhole blackhole) {
        long checksum = 0;

        for (int i = 0; i < batchSize; i++) {
            byte[] payload = new byte[payloadBytes];
            payload[0] = (byte)(allocationCount++ & 0xFF);
            checksum += payload[0];
            blackhole.consume(payload);

            // Rotate reference types: Soft -> Weak -> Phantom
            Reference<byte[]> ref;
            switch (allocationCount % 3) {
                case 0: ref = new SoftReference<>(payload, refQueue); break;
                case 1: ref = new WeakReference<>(payload, refQueue); break;
                default: ref = new PhantomReference<>(payload, refQueue); break;
            }

            if (activeRefs.size() >= MAX_REFS) {
                activeRefs.remove(0);
            }
            activeRefs.add(ref);

            // Drain reference queue periodically
            if (allocationCount % 100 == 0) {
                Reference<?> cleared;
                while ((cleared = refQueue.poll()) != null) {
                    cleared.clear();
                }
            }
        }

        if (sleepMs > 0) {
            try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return checksum;
    }
}
