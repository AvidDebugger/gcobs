package net.szumigaj.gcobs.cli.model.result;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalysisQuality(Double parseCoveragePct, Double skippedLinesPct, String phaseAttribution,
                              String dataCompleteness) {
}
