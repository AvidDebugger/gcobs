package net.szumigaj.gcobs.cli.telemetry;

import java.util.List;

class PercentileCalculator {
    static double percentile(List<Double> sorted, double pct) {
        if (sorted.isEmpty()) {
            return 0.0;
        }
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        double index = pct * (sorted.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = Math.min(lower + 1, sorted.size() - 1);
        double fraction = index - lower;
        return sorted.get(lower) + fraction * (sorted.get(upper) - sorted.get(lower));
    }
}
