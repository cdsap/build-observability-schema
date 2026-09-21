# Develocity projection

Develocity custom values are strings, while GBOS observations contain typed
values. The projection keeps the canonical observation intact and adds only a
small, explicit set of scalar indexes for frequent filters.

## Canonical custom values

Emit the schema header once per projection, then emit producer-scoped metadata and
headerless compact observations:

```text
name  = gbos.schema
value = 1.0.0

name  = gbos.v1.producer.info_test_process.version
value = 0.0.4

name  = gbos.v1.producer.info_test_process.name
value = info-test-process

name  = gbos.v1.producer.info_test_process.observation
value = <compact JSON observation fragment>
```

`gbos.schema` is global and is emitted once. Each producer gets its own bounded
namespace under `gbos.v1.producer.<producer_slug>`, so multiple plugins can
publish observations in the same build without ambiguous producer metadata.
Fragment JSON must validate against `schema/observation-fragment.schema.json` and
omits `schemaVersion` and `producer`. The producer slug replaces `-` and `.` with
`_`.

When several observations share the same producer metadata, an adapter may emit
one batch custom value instead:

```text
name  = gbos.v1.observations
value = <compact JSON observation batch>
```

The batch must validate against `schema/observation-batch.schema.json`. Its
`schemaVersion` and `producer` fields are required once at the top level, and
each child in `observations[]` must omit those fields. All children in a batch
therefore share the same schema version and producer identity. Consumers that
need self-contained observations should copy the header fields into each child
before normalizing it to `gbos.v1.observation`.

For the two-record InfoTestProcess example in `examples/test-process-batch.json`,
compact JSON is 890 bytes as one batch versus 952 bytes after expanding the
same records into two standalone observations, a 62-byte (6.5%) reduction.
The saving grows with the number of records in a batch.

The producer-scoped representation is the preferred Develocity projection. The
batch representation remains available for transports that carry one structured
value. A producer must not alternate between fragment and batch shapes under the
same custom-value name.

## Optional scalar indexes

An index is redundant data optimized for numeric filtering:

```text
gbos.v1.index.<producer_slug>.<metric_name>.<aggregation> = <numeric string>
```

Example:

```text
gbos.v1.index.info_test_process.jvm.process.cpu.time.sum = 3.05
```

Rules:

- `producer_slug` is the producer name with `-` and `.` converted to `_`.
- Only build-level aggregates should be indexed.
- Every index must be declared in `registry/develocity-indexes.json`.
- The number is encoded without a unit; the metric registry defines its unit.
- Do not add an index merely to reproduce every measurement. The observation or
  observation batch is canonical; indexes are query accelerators.

This structure avoids a subtle collision: separate plugins can report the same
metric, while producer-qualified index keys remain unique. Cross-producer SQL can
still match `gbos.v1.index.%.jvm.process.cpu.time.sum`.

## Tags

GBOS tags use this grammar:

```text
gbos:v<major>:<domain>:<condition>
```

Examples: `gbos:v1:tests:near-oom`, `gbos:v1:tests:cpu-heavy`.

Tags are for categorical filtering. Put thresholds in producer configuration and
document them; do not encode threshold numbers in tag names.

## Limits and truncation

- Keep compact observation JSON below 90,000 characters, leaving safety margin
  under Develocity's per-value limit used by the current plugins.
- Reserve capacity for other build tooling. A producer should expose a configurable
  observation budget rather than assume the entire per-build allowance.
- Keep the most useful observations using a documented ordering. For process data,
  descending CPU time is a reasonable default.
- When data is dropped, emit one build-level observation with `partial: true`, set
  `droppedObservations`, and include a diagnostic code such as
  `gbos.limit.observations_dropped`.
- Missing samples must use a diagnostic and omit unavailable measurements. Do not
  synthesize zero values because zero is a valid measurement.

## Suggested adapter boundary

Each plugin should build GBOS observation objects first. Output adapters then:

1. Render human tables from those objects.
2. Serialize a report or NDJSON file. A JSON report may use
   `observationBatches[]` when records share a producer header.
3. Serialize one observation as `gbos.v1.observation`, or a compatible group
   as `gbos.v1.observations`.
4. Derive only allowlisted scalar indexes from build-level observations after
   expanding batch children with their header metadata.

This ensures terminal, file, and Build Scan output cannot silently diverge.
