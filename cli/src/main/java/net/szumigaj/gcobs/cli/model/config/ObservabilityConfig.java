package net.szumigaj.gcobs.cli.model.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ObservabilityConfig(JfrConfig jfr, GcLogConfig gcLog, TimeSeriesConfig timeseries,
                                  PhaseBoundariesConfig phaseBoundaries, HeapDumpOnOutOfMemoryError heapDumpOnOutOfMemoryError) {
}
