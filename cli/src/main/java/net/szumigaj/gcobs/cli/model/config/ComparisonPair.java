package net.szumigaj.gcobs.cli.model.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ComparisonPair(String id, String base, String candidate, String description,
                             List<ComparisonMetric> metrics) {
}
