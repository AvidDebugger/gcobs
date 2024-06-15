package net.szumigaj.gcobs.cli.telemetry.gc.parser;

import java.io.BufferedReader;
import java.io.IOException;

/**
 * Strategy for parsing GC log lines into a ParseResult.
 * Implementations are algorithm-specific (e.g. G1, ZGC) or generic fallback.
 * Parsing is stream-based to support large log files without loading them fully into memory.
 */
public interface GcLogParserStrategy {

    /**
     * Parses GC log from a buffered input stream, reading line by line.
     *
     * @param input the GC log stream (caller retains ownership, not closed by this method)
     * @return populated ParseResult
     * @throws IOException if reading fails
     */
    ParserResult parse(BufferedReader input) throws IOException;

}
