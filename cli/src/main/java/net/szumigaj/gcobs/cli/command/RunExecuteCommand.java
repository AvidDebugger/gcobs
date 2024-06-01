package net.szumigaj.gcobs.cli.command;

import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.szumigaj.gcobs.cli.executor.BenchmarkExecutor;
import net.szumigaj.gcobs.cli.executor.ExecutionOptions;
import net.szumigaj.gcobs.cli.executor.JmhLauncher;
import net.szumigaj.gcobs.cli.executor.SourceResolver;
import net.szumigaj.gcobs.cli.model.config.BenchmarkRunSpec;
import net.szumigaj.gcobs.cli.spec.SpecLoader;
import net.szumigaj.gcobs.cli.spec.SpecValidator;
import net.szumigaj.gcobs.cli.spec.ValidationError;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Slf4j
@Command(name = "execute",
         mixinStandardHelpOptions = true,
         description = "Execute a BenchmarkRun spec")
public class RunExecuteCommand implements Callable<Integer> {

    @Option(names = "--spec", required = true,
            description = "Path to BenchmarkRun YAML")
    Path specPath;

    @Option(names = "--run-id",
            description = "Override auto-generated run ID")
    String runId;

    @Option(names = "--runs-dir",
            description = "Override output directory (default: runs/)")
    Path runsDir;

    @Option(names = "--no-jfr",
            description = "Disable JFR for all benchmarks")
    boolean noJfr;

    @Option(names = "--benchmark",
            description = "Execute only named benchmark(s) (repeatable)")
    List<String> benchmarkFilter;

    @Option(names = "--dry-run",
            description = "Validate + resolve sources; no execution")
    boolean dryRun;

    @Option(names = "--profile",
            description = "Override run.profile (invariant|explore)")
    String profile;

    @Option(names = "--strict-metrics",
            description = "Enforce missing-threshold-metric as breach")
    boolean strictMetrics;

    @Inject
    private SpecLoader loader;

    @Inject
    private SpecValidator validator;

    @Inject
    private BenchmarkExecutor executor;

    @Override
    public Integer call() {
        try {
            BenchmarkRunSpec spec = loader.load(specPath);

            List<ValidationError> errors = validator.validate(spec);
            List<String> warnings = validator.getRigorWarnings(spec);

            warnings.forEach(log::warn);

            if (!errors.isEmpty()) {
                errors.forEach(e -> log.error(e.format()));
                return 2;
            }

            Path projectRoot = specPath.toAbsolutePath().getParent();

            while (projectRoot != null
                    && !projectRoot.resolve("settings.gradle").toFile().exists()) {
                projectRoot = projectRoot.getParent();
            }
            if (projectRoot == null) {
                projectRoot = Path.of(System.getProperty("user.dir"));
            }

            // 4. Build execution options
            ExecutionOptions options = ExecutionOptions.builder()
                    .projectRoot(projectRoot)
                    .specPath(specPath)
                    .runId(runId)
                    .runsDir(runsDir)
                    .noJfr(noJfr)
                    .benchmarkFilter(benchmarkFilter)
                    .dryRun(dryRun)
                    .profile(profile)
                    .strictMetrics(strictMetrics)
                    .build();

            // 5. Execute
            return executor.execute(spec, options);
        } catch (Exception e) {
            log.error("ERROR: {}", e.getMessage(), e);
            return 2;
        }
    }
}
