package net.szumigaj.gcobs.cli.model.result;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Compilation(int count, double totalMs, double maxMs, int osrCount) {
}
