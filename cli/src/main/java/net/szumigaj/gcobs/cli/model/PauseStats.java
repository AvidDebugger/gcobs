package net.szumigaj.gcobs.cli.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PauseStats(int countTotal, int countMinor, int countMixed, int countFull, Double minMs, Double maxMs,
                         Double meanMs, Double p50Ms, Double p95Ms, Double p99Ms, Double totalMs) {
}
