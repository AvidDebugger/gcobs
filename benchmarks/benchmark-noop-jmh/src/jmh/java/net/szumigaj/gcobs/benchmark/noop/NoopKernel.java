package net.szumigaj.gcobs.benchmark.noop;

import org.openjdk.jmh.infra.Blackhole;

class NoopKernel {

    static long run(KernelParams params, Blackhole blackhole) {
        long checksum = 0;
        for (int i = 0; i < params.batchSize(); i++) {
            byte[] payload = new byte[params.payloadBytes()];
            checksum += payload.length;
            blackhole.consume(payload);
        }
        if (params.sleepMs() > 0) {
            try { Thread.sleep(params.sleepMs()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return checksum;
    }
}
