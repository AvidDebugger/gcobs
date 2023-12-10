package net.szumigaj.gcobs.cli.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.util.List;

@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JfrSummary(String benchmarkId, String runId, int forkCount, String aggregation, GcEventStats gcEvents,
                         int largeObjectAllocations, int allocationStalls, SafepointJfrStats safepoint,
                         JvmErgonomics jvmErgonomics, List<String> warnings) {
}
