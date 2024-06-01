package net.szumigaj.gcobs.cli.artifact;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import net.szumigaj.gcobs.cli.model.result.AllocationClassEntry;

import java.util.List;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiagnosticsModel(List<AllocationClassEntry> allocationHotspots,
                               CompilationInterference compilationInterference, ThreadContention threadContention) {

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
        public record CompilationInterference(int compilationsTotal, int osrCompilations, double longestCompilationMs,
                                              String note) {
    }

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
        public record ThreadContention(int monitorEvents, int parkEvents, String note) {
    }
}
