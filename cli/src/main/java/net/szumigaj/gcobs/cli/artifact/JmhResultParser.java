package net.szumigaj.gcobs.cli.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import net.szumigaj.gcobs.cli.telemetry.JsonWriter;

import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
public final class JmhResultParser {

    private JmhResultParser() {}

    public static JmhScore parse(Path benchDir) {
        Path jmhResults = benchDir.resolve("jmh-results.json");
        if (!Files.exists(jmhResults)) {
            return JmhScore.EMPTY;
        }

        try {
            JsonNode root = JsonWriter.mapper().readTree(jmhResults.toFile());
            if (!root.isArray() || root.isEmpty()) {
                return JmhScore.EMPTY;
            }

            JsonNode primary = root.get(0).path("primaryMetric");
            if (primary.isMissingNode()) {
                return JmhScore.EMPTY;
            }

            Double score = doubleOrNull(primary, "score");
            Double scoreError = doubleOrNull(primary, "scoreError");
            String scoreUnit = stringOrNull(primary, "scoreUnit");

            double[] confidence = null;
            JsonNode conf = primary.path("scoreConfidence");
            if (conf.isArray() && conf.size() == 2) {
                confidence = new double[]{conf.get(0).asDouble(), conf.get(1).asDouble()};
            }

            return new JmhScore(score, scoreError, scoreUnit, confidence);
        } catch (Exception e) {
            log.warn("WARNING: Failed to parse JMH results: {}", e.getMessage());
            return JmhScore.EMPTY;
        }
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        JsonNode val = node.get(field);
        if (val == null || val.isNull()) {
            return null;
        }
        return val.asDouble();
    }

    private static String stringOrNull(JsonNode node, String field) {
        JsonNode val = node.get(field);
        if (val == null || val.isNull()) {
            return null;
        }
        return val.asText();
    }
}
