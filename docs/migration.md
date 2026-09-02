# Migration from current plugin outputs

Adopt GBOS with one compatibility release per plugin:

1. Build the canonical observation model once.
2. Continue emitting legacy output by adapting from that model.
3. Also emit GBOS file/NDJSON and `gbos.v1.*` custom values.
4. Mark legacy keys deprecated in the release notes.
5. Remove them only in the next plugin major version.

## AndroidArtifactsSizeReport

| Current | GBOS |
|---|---|
| `<artifact-file>.size = 7000096` | Scope `build.artifact`; attribute `artifact.name=<artifact-file>`; metric `build.artifact.size=7000096`, unit `By`, aggregation `last` |

The current value is already bytes. Parse `artifact.type` from the AGP artifact
kind, not only the filename. Add `gradle.project.path` and `android.variant.name`
at collection time so downstream consumers do not need to reverse-engineer names.

## InfoGradleProcess and InfoKotlinProcess

Both plugins should emit the same `jvm.process` observation. Only the
`jvm.process.role` attribute differs.

| Current suffix | Metric | Conversion | Aggregation |
|---|---|---|---|
| `max` | `jvm.process.memory.heap.limit` | displayed GiB × 1,073,741,824 → `By` | `last` |
| `usage` | `jvm.process.memory.heap.used` | displayed GiB × 1,073,741,824 → `By` | `last` |
| `capacity` | `jvm.process.memory.heap.committed` | displayed GiB × 1,073,741,824 → `By` | `last` |
| `uptime` | `jvm.process.uptime` | minutes × 60 → `s` | `last` |
| `gcTime` | `jvm.process.gc.time` | minutes × 60 → `s` | `sum` |
| `gcType` | attribute `jvm.gc.name` | normalize known names | n/a |

Map `Gradle-Process-<pid>-*` to `jvm.process.role=gradle-daemon` and
`Kotlin-Process-<pid>-*` to `jvm.process.role=kotlin-daemon`. Store the PID in
`process.pid`; never preserve it in a custom-value name.

Important: `JdkToolsParser` currently converts KiB/bytes to powers-of-two GiB but
labels the result as `GB`. Convert directly from the raw `jstat`/JVM byte values in
the new model when possible, avoiding a rounded GiB → byte round trip.

## GCReport

| Current | GBOS |
|---|---|
| `gc-<log>-<description> = <count>` | Scope `jvm.gc`; attributes `log.file.name` and `jvm.gc.action`; metric `jvm.gc.events`, unit `{collection}`, aggregation `count` |
| `gc-<log>-total-collections` | Build/entity summary observation using `jvm.gc.events`, aggregation `sum` |
| `gc-<log>-histogram` | Histogram `jvm.gc.event.time`, unit `s`, with numeric `gte`/`lt` boundaries and integer counts |

The current histogram describes event timestamps over JVM uptime, not pause
durations. Keep that meaning explicit; use a separate future metric if pause
duration histograms are added. Store only the log basename to avoid leaking local
absolute paths.

## InfoTestProcess

| Current field/key | GBOS |
|---|---|
| `testProcess.worker.<pid>` | One `jvm.process` entity observation with `process.pid` and `jvm.process.role=test-worker` attributes |
| `task` | `gradle.task.path` attribute |
| `executor` | `gradle.test.executor` attribute |
| `xmx` | `jvm.process.memory.heap.limit`, `By`, `last` |
| `uptimeMin` | `jvm.process.uptime`, `s`, `last` |
| `cpuTimeSec` | `jvm.process.cpu.time`, `s`, `sum` |
| `cpuCoresAvg` | `jvm.process.cpu.cores`, `{core}`, `last` |
| `heapUsageGb` | `jvm.process.memory.heap.used`, `By`, `last` |
| `heapPeakGb` | `jvm.process.memory.heap.peak`, `By`, `max` |
| `metaspacePeakMb` | `jvm.process.memory.metaspace.peak`, `By`, `max` |
| `gcType` | `jvm.gc.name` attribute |
| `gcCollections` | `jvm.process.gc.collections`, `{collection}`, `count` |
| `gcTimeSec` | `jvm.process.gc.time`, `s`, `sum` |
| `jitSec` | `jvm.process.jit.time`, `s`, `sum` |
| `classesLoaded` | `jvm.process.classes.loaded`, `{class}`, `last` |
| `peakThreads` | `jvm.process.threads.peak`, `{thread}`, `max` |

Existing `testProcess.*.sum/max` custom values become a build-level
`jvm.process` observation. Frequently filtered values can additionally use the
allowlisted `gbos.v1.index.info_test_process.*` keys.

When the runtime snapshot is missing, omit the unavailable measurements and add a
warning diagnostic. Do not translate today's sentinel values (`0` or `-1`) into
measurements, because they would be indistinguishable from legitimate data.

Suggested tag migration:

| Current | GBOS |
|---|---|
| `tests:cpu-heavy` | `gbos:v1:tests:cpu-heavy` |
| `tests:near-oom` | `gbos:v1:tests:near-oom` |
| `tests:jit-bound` | `gbos:v1:tests:jit-bound` |
| `tests:no-snapshot` | `gbos:v1:tests:no-snapshot` |
