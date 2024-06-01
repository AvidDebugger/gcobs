package net.szumigaj.gcobs.cli.executor;

import com.fasterxml.jackson.databind.JsonNode;
import net.szumigaj.gcobs.cli.artifact.ArtifactWriter;
import net.szumigaj.gcobs.cli.compare.ComparisonEngine;
import net.szumigaj.gcobs.cli.model.config.BenchmarkRunSpec;
import net.szumigaj.gcobs.cli.model.config.SourceType;
import net.szumigaj.gcobs.cli.output.ConsoleTable;
import net.szumigaj.gcobs.cli.spec.SpecLoader;
import net.szumigaj.gcobs.cli.telemetry.GcAnalyzer;
import net.szumigaj.gcobs.cli.telemetry.JsonWriter;
import net.szumigaj.gcobs.cli.telemetry.JfrExtractor;
import net.szumigaj.gcobs.cli.telemetry.TimeSeriesGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkExecutorTest {

    private static final String MINIMAL_GC_LOG = """
            [0ms] Using G1
            [100ms] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->8M(256M) 5.432ms
            """;

    @TempDir
    private Path tempDir;

    private Path specPath;
    private Path runsDir;
    private Path projectRoot;

    @BeforeEach
    void setUp() throws IOException {
        runsDir = tempDir.resolve("runs");
        projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
    }

    @Test
    void successPathProducesAllArtifacts() throws IOException {
        writeMinimalSpec("ephemeral-g1");
        FakeJmhLauncher fakeLauncher = new FakeJmhLauncher(0);

        BenchmarkExecutor executor = createExecutor(fakeLauncher);
        ExecutionOptions options = ExecutionOptions.builder()
                .projectRoot(projectRoot)
                .specPath(specPath)
                .runId("test-run-001")
                .runsDir(runsDir)
                .noJfr(true)
                .benchmarkFilter(null)
                .dryRun(false)
                .build();

        BenchmarkRunSpec spec = new SpecLoader().load(specPath);
        int exitCode = executor.execute(spec, options);

        assertThat(exitCode).isZero();

        Path runDir = runsDir.resolve("test-run-001");
        assertThat(runDir).exists();
        assertThat(runDir.resolve("run-spec.yaml")).exists();
        assertThat(runDir.resolve("run.json")).exists();
        assertThat(runDir.resolve("report.md")).exists();

        Path benchDir = runDir.resolve("benchmarks").resolve("ephemeral-g1");
        assertThat(benchDir).exists();
        assertThat(benchDir.resolve("gc.log")).exists();
        assertThat(Files.readString(benchDir.resolve("gc.log"))).contains("# === Fork:");
        assertThat(benchDir.resolve("gc-summary.json")).exists();
        assertThat(benchDir.resolve("benchmark-summary.json")).exists();

        JsonNode runJson = JsonWriter.mapper().readTree(runDir.resolve("run.json").toFile());
        assertThat(runJson.get("runId").asText()).isEqualTo("test-run-001");

        String reportContent = Files.readString(runDir.resolve("report.md"));
        assertThat(reportContent)
                .contains("# GC Observatory Run Report")
                .contains("test-run-001");
    }

    @Test
    void failedBenchmarkProducesPerBenchmarkSummaryAndRunArtifacts() throws IOException {
        writeMinimalSpec("failed-bench");
        FakeJmhLauncher fakeLauncher = new FakeJmhLauncher(1);

        BenchmarkExecutor executor = createExecutor(fakeLauncher);
        ExecutionOptions options = ExecutionOptions.builder()
                .projectRoot(projectRoot)
                .specPath(specPath)
                .runId("test-run-failed")
                .runsDir(runsDir)
                .noJfr(true)
                .benchmarkFilter(null)
                .dryRun(false)
                .build();

        BenchmarkRunSpec spec = new SpecLoader().load(specPath);
        int exitCode = executor.execute(spec, options);

        assertThat(exitCode).isEqualTo(3);

        Path runDir = runsDir.resolve("test-run-failed");
        assertThat(runDir.resolve("run.json")).exists();
        assertThat(runDir.resolve("report.md")).exists();

        Path benchDir = runDir.resolve("benchmarks").resolve("failed-bench");
        assertThat(benchDir.resolve("benchmark-summary.json")).exists();

        JsonNode summary = JsonWriter.mapper().readTree(benchDir.resolve("benchmark-summary.json").toFile());
        assertThat(summary.get("status").asText()).isEqualTo("failed");
    }

    @Test
    void dryRunProducesNoFilesystemArtifacts() throws IOException {
        writeMinimalSpec("ephemeral-g1");
        FakeJmhLauncher fakeLauncher = new FakeJmhLauncher(0);

        BenchmarkExecutor executor = createExecutor(fakeLauncher);
        ExecutionOptions options = ExecutionOptions.builder()
                .projectRoot(projectRoot)
                .specPath(specPath)
                .runId("dry-run-id")
                .runsDir(runsDir)
                .noJfr(true)
                .benchmarkFilter(null)
                .dryRun(true)
                .build();

        BenchmarkRunSpec spec = new SpecLoader().load(specPath);
        int exitCode = executor.execute(spec, options);

        assertThat(exitCode).isZero();
        assertThat(runsDir.resolve("dry-run-id")).doesNotExist();
    }

    @Test
    void benchmarkFilterOnlyExecutesSelectedBenchmarks() throws IOException {
        writeTwoBenchmarkSpec();
        FakeJmhLauncher fakeLauncher = new FakeJmhLauncher(0);

        BenchmarkExecutor executor = createExecutor(fakeLauncher);
        ExecutionOptions options = ExecutionOptions.builder()
                .projectRoot(projectRoot)
                .specPath(specPath)
                .runId("filter-run")
                .runsDir(runsDir)
                .noJfr(true)
                .benchmarkFilter(List.of("bench-2"))
                .dryRun(false)
                .build();

        BenchmarkRunSpec spec = new SpecLoader().load(specPath);
        int exitCode = executor.execute(spec, options);

        assertThat(exitCode).isZero();

        Path runDir = runsDir.resolve("filter-run");
        assertThat(runDir.resolve("benchmarks").resolve("bench-1")).doesNotExist();
        assertThat(runDir.resolve("benchmarks").resolve("bench-2")).exists();

        JsonNode runJson = JsonWriter.mapper().readTree(runDir.resolve("run.json").toFile());
        assertThat(runJson.get("benchmarks")).hasSize(1);
        assertThat(runJson.get("benchmarks").get(0).get("id").asText()).isEqualTo("bench-2");
    }

    @Test
    void rigorWarnings_appearsInSummary_whenForksBelowMinimum() throws IOException {
        writeSpecWithLowRigor("low-forks-bench", 5, 5, 1);
        FakeJmhLauncher fakeLauncher = new FakeJmhLauncher(0);

        BenchmarkExecutor executor = createExecutor(fakeLauncher);
        ExecutionOptions options = ExecutionOptions.builder()
                .projectRoot(projectRoot)
                .specPath(specPath)
                .runId("rigor-run-forks")
                .runsDir(runsDir)
                .noJfr(true)
                .benchmarkFilter(null)
                .dryRun(false)
                .build();

        BenchmarkRunSpec spec = new SpecLoader().load(specPath);
        executor.execute(spec, options);

        Path benchDir = runsDir.resolve("rigor-run-forks/benchmarks/low-forks-bench");
        JsonNode summary = JsonWriter.mapper().readTree(benchDir.resolve("benchmark-summary.json").toFile());
        JsonNode warnings = summary.get("warnings");
        assertThat(warnings).isNotNull();
        assertThat(warnings.isArray()).isTrue();
        boolean hasForksWarning = false;
        for (JsonNode w : warnings) {
            if (w.asText().contains("forks") && w.asText().contains("below recommended minimum")) {
                hasForksWarning = true;
                break;
            }
        }
        assertThat(hasForksWarning).isTrue();
    }

    @Test
    void rigorWarnings_appearsInSummary_whenWarmupBelowMinimum() throws IOException {
        writeSpecWithLowRigor("low-warmup-bench", 1, 5, 3);
        FakeJmhLauncher fakeLauncher = new FakeJmhLauncher(0);

        BenchmarkExecutor executor = createExecutor(fakeLauncher);
        ExecutionOptions options = ExecutionOptions.builder()
                .projectRoot(projectRoot)
                .specPath(specPath)
                .runId("rigor-run-warmup")
                .runsDir(runsDir)
                .noJfr(true)
                .benchmarkFilter(null)
                .dryRun(false)
                .build();

        BenchmarkRunSpec spec = new SpecLoader().load(specPath);
        executor.execute(spec, options);

        Path benchDir = runsDir.resolve("rigor-run-warmup/benchmarks/low-warmup-bench");
        JsonNode summary = JsonWriter.mapper().readTree(benchDir.resolve("benchmark-summary.json").toFile());
        JsonNode warnings = summary.get("warnings");
        assertThat(warnings).isNotNull();
        assertThat(warnings.isArray()).isTrue();
        boolean hasWarmupWarning = false;
        for (JsonNode w : warnings) {
            if (w.asText().contains("warmup") && w.asText().contains("below recommended minimum")) {
                hasWarmupWarning = true;
                break;
            }
        }
        assertThat(hasWarmupWarning).isTrue();
    }

    @Test
    void timeseriesEnabled_producesTimeseriesJsonl() throws IOException {
        writeSpecWithTimeseries("timeseries-bench");
        FakeJmhLauncher fakeLauncher = new FakeJmhLauncherWithIterations(0);

        BenchmarkExecutor executor = createExecutor(fakeLauncher);
        ExecutionOptions options = ExecutionOptions.builder()
                .projectRoot(projectRoot)
                .specPath(specPath)
                .runId("timeseries-run")
                .runsDir(runsDir)
                .noJfr(true)
                .benchmarkFilter(null)
                .dryRun(false)
                .build();

        BenchmarkRunSpec spec = new SpecLoader().load(specPath);
        int exitCode = executor.execute(spec, options);

        assertThat(exitCode).isZero();

        Path benchDir = runsDir.resolve("timeseries-run/benchmarks/timeseries-bench");
        Path timeseriesFile = benchDir.resolve("metrics-timeseries.jsonl");
        assertThat(timeseriesFile).exists();
        List<String> lines = Files.readAllLines(timeseriesFile);
        assertThat(lines).hasSize(3); // 1 warmup + 2 measurement
        assertThat(lines.get(0)).contains("\"phase\":\"warmup\"");
        assertThat(lines.get(1)).contains("\"phase\":\"measurement\"");
        assertThat(lines.get(2)).contains("\"phase\":\"measurement\"");
    }

    @Test
    void timeseriesDisabled_doesNotProduceTimeseriesJsonl() throws IOException {
        writeMinimalSpec("no-timeseries-bench");
        FakeJmhLauncher fakeLauncher = new FakeJmhLauncherWithIterations(0);

        BenchmarkExecutor executor = createExecutor(fakeLauncher);
        ExecutionOptions options = ExecutionOptions.builder()
                .projectRoot(projectRoot)
                .specPath(specPath)
                .runId("no-timeseries-run")
                .runsDir(runsDir)
                .noJfr(true)
                .benchmarkFilter(null)
                .dryRun(false)
                .build();

        BenchmarkRunSpec spec = new SpecLoader().load(specPath);
        executor.execute(spec, options);

        Path benchDir = runsDir.resolve("no-timeseries-run/benchmarks/no-timeseries-bench");
        assertThat(benchDir.resolve("metrics-timeseries.jsonl")).doesNotExist();
    }

    private void writeMinimalSpec(String benchmarkId) throws IOException {
        specPath = tempDir.resolve("spec.yaml");
        Files.writeString(specPath, """
                metadata:
                  name: test-spec
                benchmarks:
                  - id: %s
                    source:
                      type: internal
                      module: benchmark-ephemeral-jmh
                """.formatted(benchmarkId));
    }

    private void writeSpecWithLowRigor(String benchmarkId, int warmupIterations, int measurementIterations, int forks) throws IOException {
        specPath = tempDir.resolve("spec.yaml");
        Files.writeString(specPath, """
                metadata:
                  name: test-spec
                jmh:
                  warmupIterations: %d
                  measurementIterations: %d
                  forks: %d
                benchmarks:
                  - id: %s
                    source:
                      type: internal
                      module: benchmark-ephemeral-jmh
                """.formatted(warmupIterations, measurementIterations, forks, benchmarkId));
    }

    private void writeTwoBenchmarkSpec() throws IOException {
        specPath = tempDir.resolve("spec.yaml");
        Files.writeString(specPath, """
                metadata:
                  name: test-spec
                benchmarks:
                  - id: bench-1
                    source:
                      type: internal
                      module: benchmark-ephemeral-jmh
                  - id: bench-2
                    source:
                      type: internal
                      module: benchmark-ephemeral-jmh
                """);
    }

    private void writeSpecWithTimeseries(String benchmarkId) throws IOException {
        specPath = tempDir.resolve("spec.yaml");
        Files.writeString(specPath, """
                metadata:
                  name: test-spec
                observability:
                  timeseries:
                    enabled: true
                benchmarks:
                  - id: %s
                    source:
                      type: internal
                      module: benchmark-ephemeral-jmh
                """.formatted(benchmarkId));
    }

    private BenchmarkExecutor createExecutor(FakeJmhLauncher fakeLauncher) {
        return new BenchmarkExecutor(
                new FakeSourceResolver(),
                fakeLauncher,
                new GcAnalyzer(),
                new FakeJfrExtractor(),
                new ArtifactWriter(),
                new ComparisonEngine(),
                new ConsoleTable(),
                new TimeSeriesGenerator()
        );
    }

    private static class FakeSourceResolver extends SourceResolver {
        @Override
        public ResolvedSource resolve(net.szumigaj.gcobs.cli.model.config.SourceConfig source, Path projectRoot) {
            return new ResolvedSource(SourceType.INTERNAL, ":benchmarks:fake:runJmh", projectRoot.resolve("benchmarks/fake"));
        }
    }

    private static class FakeJmhLauncher extends JmhLauncher {
        private final int exitCode;

        FakeJmhLauncher(int exitCode) {
            this.exitCode = exitCode;
        }

        @Override
        public int launch(ResolvedSource source, net.szumigaj.gcobs.cli.spec.EffectiveBenchmarkConfig config,
                          Path benchDir, boolean noJfr, Path projectRoot) throws IOException {
            Files.writeString(benchDir.resolve("jmh.cmdline.txt"), "fake gradle command");
            Files.writeString(benchDir.resolve("jmh.stdout.log"), "stdout");
            Files.writeString(benchDir.resolve("jmh.stderr.log"), "stderr");

            try (InputStream in = BenchmarkExecutorTest.class.getResourceAsStream("/jmh/jmh-results-sample.json")) {
                if (in != null) {
                    Files.copy(in, benchDir.resolve("jmh-results.json"));
                }
            }

            Files.writeString(benchDir.resolve("gc-1.log"), MINIMAL_GC_LOG);
            Files.writeString(benchDir.resolve("gc-2.log"), MINIMAL_GC_LOG);

            return exitCode;
        }
    }

    private static class FakeJmhLauncherWithIterations extends FakeJmhLauncher {
        FakeJmhLauncherWithIterations(int exitCode) {
            super(exitCode);
        }

        @Override
        public int launch(ResolvedSource source, net.szumigaj.gcobs.cli.spec.EffectiveBenchmarkConfig config,
                          Path benchDir, boolean noJfr, Path projectRoot) throws IOException {
            int code = super.launch(source, config, benchDir, noJfr, projectRoot);
            Files.writeString(benchDir.resolve("jmh.stdout.log"), """
                    # Warmup Iteration   1: 0.015000 ms/op
                    Iteration   1: 0.015234 ms/op
                    Iteration   2: 0.016100 ms/op
                    """);
            return code;
        }
    }

    private static class FakeJfrExtractor extends JfrExtractor {
        @Override
        public net.szumigaj.gcobs.cli.model.result.JfrSummary extract(Path benchDir, String benchmarkId, String runId) {
            return null;
        }
    }
}
