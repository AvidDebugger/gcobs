package net.szumigaj.gcobs.cli.model.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

import java.util.Map;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Metadata(String name, String description, Map<String, String> labels) {
}
