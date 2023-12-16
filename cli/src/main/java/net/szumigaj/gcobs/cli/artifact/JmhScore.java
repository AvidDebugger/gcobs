package net.szumigaj.gcobs.cli.artifact;

/**
 * Parsed JMH primary metric data extracted from jmh-results.json.
 */
public record JmhScore(
        Double score,
        Double scoreError,
        String scoreUnit,
        double[] scoreConfidenceInterval
) {
    public static final JmhScore EMPTY = new JmhScore(null, null, null, null);
}
