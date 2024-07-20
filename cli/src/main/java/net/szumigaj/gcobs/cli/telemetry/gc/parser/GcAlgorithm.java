package net.szumigaj.gcobs.cli.telemetry.gc.parser;

/**
 * Known GC algorithms, used by {@link GcLogParserStrategy#supports(GcAlgorithm)}
 * for type-safe dispatch instead of raw string matching.
 */
public enum GcAlgorithm {
    G1,
    ZGC,
    PARALLEL,
    SERIAL,
    SHENANDOAH,
    EPSILON,
    UNKNOWN;

    /**
     * Maps a raw token captured from a GC log header to a {@code GcAlgorithm}.
     * All JVM-specific spellings (e.g. "Initializing The Z Garbage Collector") are resolved here.
     */
    static GcAlgorithm fromLogToken(String token) {
        return switch (token) {
            case "Using G1"                              -> G1;
            case "ZGC", "Initializing The Z Garbage Collector" -> ZGC;
            case "Parallel"                              -> PARALLEL;
            case "Serial"                                -> SERIAL;
            case "Shenandoah"                            -> SHENANDOAH;
            case "Epsilon"                               -> EPSILON;
            default                                      -> UNKNOWN;
        };
    }
}
