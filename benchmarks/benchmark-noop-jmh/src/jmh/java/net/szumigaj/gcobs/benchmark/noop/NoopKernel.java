package net.szumigaj.gcobs.benchmark.noop;

class NoopKernel {

    static long run(KernelParams params, Object[] sink) {
        long checksum = 0;
        for (int i = 0; i < params.batchSize(); i++) {
            byte[] payload = new byte[params.payloadBytes()];
            checksum += payload.length;
            sink[0] = payload;
        }
        if (params.sleepMs() > 0) {
            try { Thread.sleep(params.sleepMs()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return checksum;
    }
}
