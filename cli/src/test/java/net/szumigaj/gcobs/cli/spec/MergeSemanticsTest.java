package net.szumigaj.gcobs.cli.spec;

import net.szumigaj.gcobs.cli.model.config.BenchmarkRunSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class MergeSemanticsTest {

    private static BenchmarkRunSpec spec;
    private static final SpecLoader loader = new SpecLoader();

    @BeforeAll
    static void loadSpec() throws IOException {
        spec = loader.load(Path.of("src/test/resources/specs/merge-test.yaml"));
    }

    @Test
    void jvmArgs_inheritedWhenNotOverridden() {
        // benchmarks[0] "inherits-all" - no jvm override
        EffectiveBenchmarkConfig eff = loader.getEffective(spec, 0);
        assertThat(eff.jvmArgs()).containsExactly("-Xms384m", "-Xmx384m");
    }

    @Test
    void jvmArgs_replacedEntirely() {
        // benchmarks[1] "overrides-jvm" - overrides jvm.args
        EffectiveBenchmarkConfig eff = loader.getEffective(spec, 1);
        assertThat(eff.jvmArgs())
                .containsExactly("-XX:+UseZGC", "-Xms512m", "-Xmx512m")
                .doesNotContain("-Xms384m", "-Xmx384m"); // Must NOT contain top-level args
    }

    @Test
    void jvmEnv_inherited() {
        // benchmarks[0] "inherits-all" - no env override
        EffectiveBenchmarkConfig eff = loader.getEffective(spec, 0);
        assertThat(eff.jvmEnv())
                .containsEntry("JAVA_HOME", "/usr/lib/jvm/java-17")
                .containsEntry("SHARED_KEY", "top-level-value");
    }

    @Test
    void jvmEnv_mergedBenchmarkWins() {
        // benchmarks[1] "overrides-jvm" - merges env, benchmark wins on conflict
        EffectiveBenchmarkConfig eff = loader.getEffective(spec, 1);
        assertThat(eff.jvmEnv())
                .containsEntry("JAVA_HOME", "/usr/lib/jvm/java-17") // inherited
                .containsEntry("SHARED_KEY", "bench-level-value")    // overridden
                .containsEntry("BENCH_ONLY", "extra");               // benchmark-only
    }

    @Test
    void jmh_allInherited() {
        // benchmarks[0] "inherits-all" - no jmh override
        EffectiveBenchmarkConfig eff = loader.getEffective(spec, 0);
        assertThat(eff.warmupIterations()).isEqualTo(5);
        assertThat(eff.measurementIterations()).isEqualTo(10);
        assertThat(eff.forks()).isEqualTo(3);
        assertThat(eff.threads()).isEqualTo(2);
    }

    @Test
    void jmh_fieldLevelOverride() {
        // benchmarks[2] "overrides-jmh" - forks=1, threads=4, rest inherited
        EffectiveBenchmarkConfig eff = loader.getEffective(spec, 2);
        assertThat(eff.forks()).isEqualTo(1);
        assertThat(eff.threads()).isEqualTo(4);
        assertThat(eff.warmupIterations()).isEqualTo(5);       // inherited
        assertThat(eff.measurementIterations()).isEqualTo(10);  // inherited
    }

    @Test
    void observability_inherited() {
        // benchmarks[0] "inherits-all" - observability from top-level
        EffectiveBenchmarkConfig eff = loader.getEffective(spec, 0);
        assertThat(eff.jfrEnabled()).isTrue();
        assertThat(eff.jfrSettings()).isEqualTo("profile");
        assertThat(eff.timeseriesEnabled()).isFalse();
    }

    @Test
    void observability_fieldLevelOverride() {
        // benchmarks[3] "overrides-observability" - jfr.enabled=false, timeseries.enabled=true
        EffectiveBenchmarkConfig eff = loader.getEffective(spec, 3);
        assertThat(eff.jfrEnabled()).isFalse();            // overridden
        assertThat(eff.timeseriesEnabled()).isTrue();       // overridden
        assertThat(eff.jfrSettings()).isEqualTo("profile"); // inherited (jfr.settings not overridden)
    }

    @Test
    void defaults_whenNothingSpecified() throws IOException {
        // A spec with no top-level defaults and no per-benchmark overrides
        var minSpec = loader.load(Path.of("src/test/resources/specs/valid-minimal.yaml"));
        EffectiveBenchmarkConfig eff = loader.getEffective(minSpec, 0);

        assertThat(eff.warmupIterations()).isEqualTo(5);
        assertThat(eff.measurementIterations()).isEqualTo(5);
        assertThat(eff.forks()).isEqualTo(3);
        assertThat(eff.threads()).isEqualTo(1);
        assertThat(eff.jfrEnabled()).isTrue();
        assertThat(eff.jfrSettings()).isEqualTo("profile");
        assertThat(eff.gcLogTags()).isEqualTo("gc*,safepoint*,gc+promotion");
        assertThat(eff.timeseriesEnabled()).isFalse();
        assertThat(eff.phaseBoundariesEnabled()).isFalse();
        assertThat(eff.jvmArgs()).isEmpty();
        assertThat(eff.jvmEnv()).isEmpty();
    }
}
