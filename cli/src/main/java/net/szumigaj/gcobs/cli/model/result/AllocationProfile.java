package net.szumigaj.gcobs.cli.model.result;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.util.List;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AllocationProfile(int totalEvents, int outsideTlabEvents, int inNewTlabEvents,
                                List<AllocationClassEntry> topClassesByCount) {
}
