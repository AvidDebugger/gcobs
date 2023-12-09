package net.szumigaj.gcobs.cli.model;

public record CollectionEvent(
        int n,
        String type,
        String cause,
        int beforeMb,
        int afterMb,
        int capacityMb,
        double durationMs,
        long uptimeMs
) {}
