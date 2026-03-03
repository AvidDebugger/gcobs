# gcobs

Java GC Observatory: a CLI for running JMH workloads under different JVM garbage collectors and collecting GC/JFR artifacts in a repeatable way.

gcobs executes benchmarks described in a YAML “run spec”, injects GC log / JFR flags, and writes a `runs/<run-id>/` directory with:
- per-benchmark raw JMH output (`jmh-results.json`) and command line (`jmh.cmdline.txt`)
- parsed summaries (`benchmark-summary.json`, `gc-summary.json`, `jfr-summary.json`)
- run-level manifest (`run.json`) and a Markdown report (`report.md`)
- optional intra-run comparisons (`compare/<pair-id>/compare-result.json`)

## Quick start

Prerequisites:
- Java 21 (build and run)

Build the CLI fat JAR:
```bash
./gradlew :cli:shadowJar
```

Run a built-in spec (writes to `runs/`, which is gitignored):
```bash
./gcobs execute --spec run-spec/lab01.yaml
```

See CLI help:
```bash
./gcobs --help
./gcobs execute --help
```

Run without the wrapper script:
```bash
java -jar cli/build/libs/cli-all.jar --help
java -jar cli/build/libs/cli-all.jar execute --spec run-spec/lab01.yaml
```

## Included run specs

Specs live in `run-spec/`:

- `lab01.yaml`: single benchmark + read `gc-summary.json` and `jfr-summary.json`
- `lab02.yaml`: run two benchmarks and compare them (`compare-result.json`)
- `lab03.yaml`: service-latency lab using ZGC
- `gc-algorithms-batch.yaml`: compare collectors on a batch workload
- `gc-algorithms-burst.yaml`: stress collectors with burst allocation pressure
- `heap-sizing.yaml`: compare heap sizes on the same workload
- `reference-gc-comparison.yaml`: stress reference processing (Soft/Weak/Phantom)
- `graph-gc-comparison.yaml`: stress marking with a reference graph workload
- `humongous-gc-comparison.yaml`: stress G1 humongous allocations
- `mixed-gc-comparison.yaml`: mixed batch + service workload
- `zgc-vs-g1-batch.yaml`: hypothesis spec for ZGC vs G1 (batch)
- `zgc-vs-g1-service.yaml`: hypothesis spec for ZGC vs G1 (service)
- `epsilon-baseline.yaml`: baseline allocation cost with EpsilonGC vs G1
- `all-baseline.yaml`: baseline run across many collectors

## Running a spec

Basic form:
```bash
./gcobs execute --spec path/to/spec.yaml
```

Useful flags:
- `--runs-dir <dir>`: override output directory (default: `runs/`)
- `--run-id <id>`: override auto-generated run id
- `--benchmark <id>`: run only specific benchmark(s) (repeatable)
- `--no-jfr`: disable JFR even if the spec enables it
- `--profile invariant|explore`: override `run.profile`
- `--dry-run`: validate spec and print effective config without executing benchmarks
- `--strict-metrics`: treat missing threshold metrics as a breach

Exit codes:
- `0`: all benchmarks succeeded and (in `invariant` mode) no threshold breaches
- `2`: spec validation error or unexpected failure
- `3`: at least one benchmark failed, or an invariant threshold was breached

## Spec format

A run spec is YAML with these top-level blocks:
- `metadata`: required (`metadata.name` must match `^[a-z0-9][a-z0-9-]*$`)
- `run`: optional (profile and output directory)
- `jvm`, `jmh`, `observability`: optional defaults applied to benchmarks
- `benchmarks`: required list of benchmarks to execute
- `compare`: optional base/candidate comparisons computed after the run

Minimal example:
```yaml
metadata:
  name: my-first-run

benchmarks:
  - id: g1-ephemeral
    source:
      type: internal
      module: benchmark-ephemeral-jmh
```

More complete example (single benchmark):
```yaml
metadata:
  name: g1-baseline

run:
  profile: invariant
  runsDir: runs/

jmh:
  warmupIterations: 3
  measurementIterations: 3
  forks: 2
  threads: 1

observability:
  jfr:
    enabled: true
    settings: profile
  timeseries:
    enabled: true

benchmarks:
  - id: g1-batch
    source:
      type: internal
      module: benchmark-batch-jmh
    jvm:
      args: [-XX:+UseG1GC, -Xms256m, -Xmx256m]
    jmh:
      includes: ".*batchKernelChecksum.*"
    params:
      iterations: 10
      batchSize: 80
      payloadBytes: 320
      sleepMs: 0
    thresholds:
      gcFullCountMax: 0
      gcOverheadMaxPct: 25.0
```

### Merge semantics (top-level defaults vs per-benchmark overrides)

- `jvm.args`: benchmark-level replaces top-level (no automatic append)
- `jmh.*`: benchmark-level overrides individual fields (others inherit)
- `observability.*`: benchmark-level overrides JFR/timeseries toggles and settings

gcobs injects GC logging and JFR JVM flags automatically. The spec validator rejects user-supplied flags like:
- `-Xlog:gc...`
- `-XX:StartFlightRecording=...`
- `-XX:FlightRecorderOptions=...`

### Benchmark sources

Each benchmark has a `source`:
- `type: internal`: run a benchmark module from this repo (under `benchmarks/`)
  ```yaml
  source:
    type: internal
    module: benchmark-batch-jmh
  ```
- `type: jar`: run JMH from a jar on disk (must have JMH on the classpath, typically a fat jar)
  ```yaml
  source:
    type: jar
    path: /abs/or/relative/path/to/benchmarks-all.jar
  ```
- `type: gradle`: build an external Gradle project and run its produced jar
  ```yaml
  source:
    type: gradle
    projectDir: ../some-jmh-project
    buildTask: shadowJar
    jarPattern: build/libs/*-all.jar
    gradleExecutable: ./gradlew
  ```

## Artifacts

By default, runs are written to `runs/<run-id>/`:

```
runs/<run-id>/
  run-spec.yaml
  run.json
  report.md
  compare/<pair-id>/compare-result.json
  benchmarks/<benchmark-id>/
    benchmark-summary.json
    jmh-results.json
    jmh.cmdline.txt
    jmh.stdout.log
    jmh.stderr.log
    gc-<pid>.log             # per-fork GC logs
    gc.log                   # merged GC log
    gc-summary.json
    gc-summary-warmup.json   # when available
    profile-<pid>.jfr        # when JFR enabled
    jfr-summary.json         # when JFR enabled
    metrics-timeseries.jsonl # when timeseries enabled
```

Start with:
- `report.md`: high-level table for the run
- `run.json`: machine-readable manifest (spec hash, environment snapshot, per-benchmark metrics)
- `benchmarks/<id>/benchmark-summary.json`: effective JVM/JMH config + artifact list

## Thresholds and comparisons

### Thresholds

Thresholds are declared per benchmark under `thresholds:` and are treated as “maximum allowed” values (lower is better). Supported fields:
- `gcOverheadMaxPct`
- `gcPauseP95MaxMs`, `gcPauseP99MaxMs`, `gcPauseMaxMs`
- `gcFullCountMax`
- `gcAllocationStallsMax` (from JFR)
- `heapPeakUsedMaxMb`
- `gcCpuMaxPct`
- `gcSystemGcCountMax`
- `metaspaceUsedMaxMb` (from JFR)
- `jmhScoreRegressionMaxPct` (lower is better; higher score = regression)

In `run.profile: invariant`, threshold breaches contribute to a non-zero exit code.
In `run.profile: explore`, thresholds are still evaluated and recorded, but do not fail the run.

Missing metrics:
- `run.validation.onMissingMetric: fail|skip` controls whether missing telemetry becomes a breach.
- `--strict-metrics` forces `onMissingMetric=fail`.

### Comparisons

A spec may include `compare.pairs` to compare base vs candidate benchmarks from the same run. If `metrics:` is omitted, gcobs uses defaults:
- `gcPauseP99Ms` (20% regression threshold)
- `gcOverheadPct` (20%)
- `jmhScore` (15%)

Supported comparison metrics (all “lower is better”):
- `gcPauseP99Ms`, `gcPauseP95Ms`, `gcPauseMaxMs`
- `gcOverheadPct`, `gcCpuPct`
- `gcCountFull`
- `heapPeakUsedMb`
- `jmhScore`
- `allocationStalls`
- `compilationTotalMs` (diagnostic-only; excluded from verdict)

## Built-in workloads

Internal benchmarks live under `benchmarks/` and are backed by kernels in `benchmarks/workload-common`:

- `benchmark-ephemeral-jmh`: ephemeral allocation (young-gen focused)
- `benchmark-batch-jmh`: batch allocation with retention (promotion + old-gen pressure)
- `benchmark-humongous-jmh`: large (humongous) allocations (stresses G1 humongous paths)
- `benchmark-graph-jmh`: object graph construction (stresses marking)
- `benchmark-reference-jmh`: Soft/Weak/Phantom references (stresses reference processing)
- `benchmark-mixed-jmh`: alternating batch bursts and service-style request handling
- `benchmark-fake-http-service-jmh`: simple client/server request simulations with pooling and injected failures

## Development

Run tests:
```bash
./gradlew test
```

Build the CLI jar:
```bash
./gradlew :cli:shadowJar
```

Run the CLI from source (no jar build):
```bash
./gradlew :cli:run --args="execute --spec run-spec/lab01.yaml --dry-run"
```

## Notes

- Collector availability depends on your JDK build/vendor (for example, Shenandoah and Epsilon may require specific builds or flags).
- `runs/` is in `.gitignore` by default.
