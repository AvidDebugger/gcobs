package net.szumigaj.gcobs.cli.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ObservabilityConfig(JfrConfig jfr, GcLogConfig gcLog, TimeseriesConfig timeseries,
                                  PhaseBoundariesConfig phaseBoundaries, HeapDumpOnOutOfMemoryError heapDumpOnOutOfMemoryError) {
}
