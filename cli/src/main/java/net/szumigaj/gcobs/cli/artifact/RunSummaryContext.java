package net.szumigaj.gcobs.cli.artifact;

import net.szumigaj.gcobs.cli.compare.CompareResult;

import java.util.List;

/**
 * Wraps RunContext with Phase 14 enrichment data (comparison results)
 * for run.json writing.
 */
public record RunSummaryContext(
        List<CompareResult> comparisons
) {}
