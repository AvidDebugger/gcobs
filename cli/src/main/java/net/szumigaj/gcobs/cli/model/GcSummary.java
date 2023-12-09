package net.szumigaj.gcobs.cli.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.util.List;
import java.util.Map;

@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GcSummary(String benchmarkId, String runId, String gcAlgorithm, Long runDurationMs, String analysisScope,
                        Double gcOverheadPct, Double gcCpuPct, AnalysisQuality analysisQuality, PauseStats pause,
                        HeapStats heap, SafepointStats safepoint, List<CollectionEvent> collections,
                        Map<String, CauseEntry> causeBreakdown, List<String> warnings) {
}
