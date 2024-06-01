package net.szumigaj.gcobs.cli.model.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ValidationConfig(MissingMetricPolicy onMissingMetric, Integer minParseCoveragePct) {
}
