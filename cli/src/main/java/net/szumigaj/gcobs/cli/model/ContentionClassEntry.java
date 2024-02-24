package net.szumigaj.gcobs.cli.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContentionClassEntry(String className, int count, double totalMs) {
}
