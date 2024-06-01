package net.szumigaj.gcobs.cli.model.result;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.util.List;

@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JvmErgonomics(String gcAlgorithm, Integer parallelGcThreads, Integer concGcThreads,
                            Integer g1HeapRegionSize, Integer softRefLruPolicyMsPerMb, List<String> effectiveFlags) {
}
