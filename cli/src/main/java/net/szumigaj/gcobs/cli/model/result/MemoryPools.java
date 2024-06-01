package net.szumigaj.gcobs.cli.model.result;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MemoryPools(Metaspace metaspace, OldGen oldGen) {

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Metaspace(Double usedMaxMb, Double committedMaxMb, Double reservedMb) {
    }

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OldGen(Double peakUsedMb, Double peakCommittedMb) {
    }
}
