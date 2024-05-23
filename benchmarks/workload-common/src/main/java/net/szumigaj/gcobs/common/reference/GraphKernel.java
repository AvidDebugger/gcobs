package net.szumigaj.gcobs.common.reference;

import lombok.Builder;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Creates a graph of interconnected objects with varying reference depth.
 * Tests GC marking phase performance, marking duration is proportional to
 * the number of live object references that must be traced.
 *
 * GC paths stressed:
 *   - Marking phase: deep traversal of reference graph
 *   - Complex reachability analysis
 *   - Write barrier overhead for reference updates
 *
 * Key observations:
 *   - ZGC concurrent marking handles complex graphs with minimal pause
 *   - G1GC marking time increases with graph depth and connectivity
 */
public class GraphKernel {

    private static final int DEFAULT_MAX_NODES = 4096;
    private static final int MIN_REFERENCES = 2;
    private static final int MAX_REFERENCES = 4;

    private final List<GraphNode> activeNodes;
    private final Random random = new Random(42); // Deterministic for reproducibility

    private final int payloadBytes;
    private final int batchSize;
    private final int sleepMs;
    private final int maxNodes;

    private int allocationCount = 0;

    @Builder
    private GraphKernel(int payloadBytes, int batchSize, int sleepMs, int maxNodes) {
        this.payloadBytes = payloadBytes;
        this.batchSize = batchSize;
        this.sleepMs = sleepMs;
        this.maxNodes = maxNodes > 0 ? maxNodes : DEFAULT_MAX_NODES;
        this.activeNodes = new ArrayList<>(this.maxNodes);
    }

    public long run(Blackhole blackhole) {
        long checksum = 0;

        for (int i = 0; i < batchSize; i++) {
            GraphNode node = new GraphNode(allocationCount, payloadBytes);
            node.payload[0] = (byte)(allocationCount & 0xFF);
            checksum += node.payload[0];
            blackhole.consume(node);
            allocationCount++;

            // Connect to random existing nodes (creates complex reference graph)
            if (!activeNodes.isEmpty()) {
                int refCount = MIN_REFERENCES + random.nextInt(MAX_REFERENCES - MIN_REFERENCES + 1);
                for (int r = 0; r < refCount && !activeNodes.isEmpty(); r++) {
                    int targetIdx = random.nextInt(activeNodes.size());
                    GraphNode target = activeNodes.get(targetIdx);
                    node.references.add(target);
                }
            }

            // Maintain sliding window of live nodes
            if (activeNodes.size() >= maxNodes) {
                activeNodes.remove(0);
            }
            activeNodes.add(node);
        }

        if (sleepMs > 0) {
            try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        return checksum;
    }

    public int getActiveNodesSize() {
        return activeNodes.size();
    }

    public long getTotalReferenceCount() {
        return activeNodes.stream()
                .mapToLong(node -> node.references.size())
                .sum();
    }

    static class GraphNode {
        byte[] payload;
        List<GraphNode> references;
        int id;

        GraphNode(int id, int payloadSize) {
            this.id = id;
            this.payload = new byte[payloadSize];
            this.references = new ArrayList<>(MAX_REFERENCES);
        }
    }
}
