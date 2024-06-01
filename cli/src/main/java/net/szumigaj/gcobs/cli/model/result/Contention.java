package net.szumigaj.gcobs.cli.model.result;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.util.List;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Contention(int monitorEvents, int parkEvents, double totalBlockedMs, double maxBlockedMs,
                         List<ContentionClassEntry> topMonitorClasses) {
}
