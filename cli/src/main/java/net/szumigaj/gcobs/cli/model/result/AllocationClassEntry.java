package net.szumigaj.gcobs.cli.model.result;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AllocationClassEntry(String className, int count, long totalBytes) {
}
