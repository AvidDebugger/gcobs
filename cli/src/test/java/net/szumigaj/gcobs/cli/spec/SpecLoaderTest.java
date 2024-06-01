package net.szumigaj.gcobs.cli.spec;

import net.szumigaj.gcobs.cli.model.config.BenchmarkRunSpec;
import net.szumigaj.gcobs.cli.model.config.MissingMetricPolicy;
import net.szumigaj.gcobs.cli.model.config.SourceType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class SpecLoaderTest {

    private final SpecLoader loader = new SpecLoader();

    private Path fixtureDir() {
        return Path.of("src/test/resources/specs");
    }

    @Test
    void loadMinimalSpec() throws IOException {
        BenchmarkRunSpec spec = loader.load(fixtureDir().resolve("valid-minimal.yaml"));

        assertThat(spec.metadata().name()).isEqualTo("test-minimal");
        assertThat(spec.benchmarks()).hasSize(1);
        assertThat(spec.benchmarks().get(0).id()).isEqualTo("ephemeral-g1");
        assertThat(spec.benchmarks().get(0).source().type()).isEqualTo(SourceType.INTERNAL);
        assertThat(spec.benchmarks().get(0).source().module()).isEqualTo("benchmark-ephemeral-jmh");
    }

    @Test
    void loadFullSpec() throws IOException {
        BenchmarkRunSpec spec = loader.load(fixtureDir().resolve("valid-full.yaml"));

        assertThat(spec.metadata().name()).isEqualTo("full-test-spec");
        assertThat(spec.metadata().description()).isNotEmpty();
        assertThat(spec.metadata().labels()).containsEntry("team", "platform");

        // run config
        assertThat(spec.run().profile()).isEqualTo("invariant");
        assertThat(spec.run().runId()).isEqualTo("test-run-001");
        assertThat(spec.run().validation().onMissingMetric()).isEqualTo(MissingMetricPolicy.FAIL);
        assertThat(spec.run().validation().minParseCoveragePct()).isEqualTo(95);

        // top-level jvm
        assertThat(spec.jvm().args()).contains("-Xms256m", "-Xmx256m", "-XX:+UseG1GC");
        assertThat(spec.jvm().env()).containsEntry("JAVA_HOME", "/usr/lib/jvm/java-17");

        // top-level jmh
        assertThat(spec.jmh().warmupIterations()).isEqualTo(5);
        assertThat(spec.jmh().measurementIterations()).isEqualTo(10);
        assertThat(spec.jmh().forks()).isEqualTo(3);
        assertThat(spec.jmh().threads()).isEqualTo(2);

        // observability
        assertThat(spec.observability().jfr().enabled()).isTrue();
        assertThat(spec.observability().jfr().settings()).isEqualTo("profile");
        assertThat(spec.observability().timeseries().enabled()).isFalse();

        // benchmarks
        assertThat(spec.benchmarks()).hasSize(2);
        assertThat(spec.benchmarks().get(0).thresholds().gcOverheadMaxPct()).isEqualTo(5.0);
        assertThat(spec.benchmarks().get(0).thresholds().gcFullCountMax()).isZero();
        assertThat(spec.benchmarks().get(0).params()).containsEntry("batchSize", "1000");

        // compare
        assertThat(spec.compare().pairs()).hasSize(1);
        assertThat(spec.compare().pairs().get(0).base()).isEqualTo("ephemeral-g1");
        assertThat(spec.compare().pairs().get(0).candidate()).isEqualTo("ephemeral-zgc");
        assertThat(spec.compare().pairs().get(0).metrics()).hasSize(2);
    }

    @Test
    void unknownFieldsAreIgnored() throws IOException {
        // Create a temp spec with an unknown field
        Path tempSpec = Files.createTempFile("spec-unknown-", ".yaml");
        Files.writeString(tempSpec, """
                metadata:
                  name: test-unknown
                unknownTopLevel: should-be-ignored
                benchmarks:
                  - id: ephemeral-g1
                    source:
                      type: internal
                      module: benchmark-ephemeral-jmh
                    unknownField: also-ignored
                """);

        BenchmarkRunSpec spec = loader.load(tempSpec);
        assertThat(spec.benchmarks()).hasSize(1);

        Files.deleteIfExists(tempSpec);
    }

    @Test
    void malformedYamlThrowsException() {
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
        Path badFile = tempDir.resolve("bad-yaml-" + System.nanoTime() + ".yaml");

        assertThatThrownBy(() -> {
            try {
                Files.writeString(badFile, "{{{{ not valid yaml ::::");
                loader.load(badFile);
            } finally {
                Files.deleteIfExists(badFile);
            }
        }).isInstanceOf(IOException.class);
    }
}
