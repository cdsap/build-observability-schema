# Gradle Build Observability Schema (GBOS)

GBOS is a small semantic contract for build metrics produced by Gradle plugins.
It keeps file/terminal output and Develocity Build Scan custom values aligned, so
the same `jq` and SQL logic can work across plugins.

The first adopters are:

- [AndroidArtifactsSizeReport](https://github.com/cdsap/AndroidArtifactsSizeReport)
- [InfoGradleProcess](https://github.com/cdsap/InfoGradleProcess)
- [InfoKotlinProcess](https://github.com/cdsap/InfoKotlinProcess)
- [GCReport](https://github.com/cdsap/GCReport)
- [InfoTestProcess](https://github.com/cdsap/InfoTestProcess)
- [build-observability-client](https://github.com/cdsap/build-observability-client)

Status: **proposal / v1.0.0 draft**. Existing keys can be dual-published during migration.

## The contract

Every producer emits one or more self-contained observations:

```json
{
  "schemaVersion": "1.0.0",
  "producer": {
    "name": "info-test-process",
    "version": "2.1.0"
  },
  "scope": "jvm.process",
  "aggregationScope": "entity",
  "attributes": {
    "process.pid": 13402,
    "jvm.process.role": "test-worker",
    "gradle.task.path": ":test"
  },
  "measurements": [
    {
      "name": "jvm.process.cpu.time",
      "value": 3.05,
      "unit": "s",
      "aggregation": "sum"
    }
  ]
}
```

The same observation object is used in both output paths. Producers may also
group observations that share metadata in an additive batch envelope:

```json
{
  "schemaVersion": "1.0.0",
  "producer": { "name": "info-test-process", "version": "2.1.0" },
  "observations": [
    {
      "scope": "jvm.process",
      "aggregationScope": "entity",
      "attributes": { "process.pid": 13402 },
      "measurements": [
        { "name": "jvm.process.cpu.time", "value": 3.05, "unit": "s", "aggregation": "sum" }
      ]
    }
  ]
}
```

The batch header supplies `schemaVersion` and `producer` for every child. Child
observations must not repeat or override those fields. Batches are transport
envelopes; standalone observations remain canonical and self-contained.

The same object is used in the output paths:

| Sink | Representation |
|---|---|
| JSON file | A report envelope containing `observations[]` or `observationBatches[]` |
| Terminal / streaming | One compact observation per line (NDJSON), or one batch envelope where supported |
| Develocity | `gbos.v1.observation` for one observation, or `gbos.v1.observations` for one batch envelope |
| Develocity fast filter | Optional scalar `gbos.v1.index.<producer>.<metric>.<aggregation>` |

## Design rules

1. **Keys are stable; identity is data.** PID, task path, artifact name, variant,
   and log filename belong in `attributes`, never in a metric/custom-value name.
2. **Values are typed.** Numeric measurements are JSON numbers. Units are not
   concatenated into values.
3. **Use base units.** Bytes (`By`), seconds (`s`), dimensionless values (`1`),
   or annotated counts such as `{thread}`. Do not emit GB/MB/minutes.
4. **Names use lowercase dotted segments.** Example: `jvm.process.gc.time`.
   Segment words use `snake_case`.
5. **Aggregation is explicit.** `last`, `min`, `max`, `sum`, or `count` is
   separate from the metric name.
6. **Every standalone observation identifies its producer and schema version.**
   A batch identifies them once in its header; adapters expand child records
   into standalone observations when a consumer requires self-contained values.
7. **Partial data is visible.** Set `partial`, `droppedObservations`, and/or
   `diagnostics`; never represent missing measurements as zero.

These choices follow OpenTelemetry's hierarchy and attribute guidance and its
UCUM base-unit recommendations, while keeping the transport deliberately much
smaller than OTLP.

## Repository layout

```text
schema/                         JSON Schema 2020-12 contracts
                                observation-batch.schema.json defines shared-header batches
registry/semantic-conventions.json
                                Allowed scopes, metrics, units, and attributes
registry/develocity-indexes.json
                                Explicit allowlist of denormalized scalar indexes
examples/                       One report per current plugin
develocity/                     Build Scan projection example
docs/migration.md               Current key -> GBOS mapping
docs/develocity-projection.md   Limits, indexing, and emission rules
docs/querying.md                jq and Develocity Analytics examples
scripts/validate.py             Semantic checks beyond JSON Schema
```

## Validate

The repository's CI validates all JSON Schemas and examples. Locally:

```bash
python3 scripts/validate.py
```

For full JSON Schema validation, use an isolated virtual environment and run:

```bash
python3 -m venv .venv
. .venv/bin/activate
python3 -m pip install -r requirements.txt
.venv/bin/check-jsonschema --check-metaschema schema/*.json
.venv/bin/check-jsonschema --schemafile schema/report.schema.json examples/*.json
.venv/bin/check-jsonschema --schemafile schema/develocity-projection.schema.json develocity/*.json
.venv/bin/check-jsonschema --schemafile schema/semantic-conventions.schema.json registry/semantic-conventions.json
.venv/bin/check-jsonschema --schemafile schema/develocity-indexes.schema.json registry/develocity-indexes.json
```

## JVM artifact

The schemas and registries are also packaged as a resource-only Maven artifact
for producer tests and CI. The initial development version is:

```text
io.github.cdsap:build-observability-schema:0.0.4
```

It is not a runtime dependency of producer plugins. To build and inspect the
artifact locally:

```bash
./gradlew verifyArtifactLayout verifyJavaTargetMetadata
./gradlew publishToMavenLocal
```

The Maven-local publication is signed, so the second command requires the
signing properties described in [releasing](docs/releasing.md). The artifact
contains `schema/*.schema.json` and `registry/*.json`. Consumer repositories
should use it with `testImplementation` and validate generated documents with a
JSON Schema validator appropriate for their language.

An `io.github.cdsap:build-observability-core` module is also published by this repository's
release process.
It contains the shared typed observation model and JSON encoding mechanics as a separate
optional runtime artifact. Producer integration should follow the release process in
`docs/releasing.md` and compatibility verification.

## Versioning

- `schemaVersion` uses semantic versioning.
- Develocity keys include only the major version: `gbos.v1.*`.
- Additive registry entries and optional fields are minor changes.
- Removing/renaming a field, metric, unit, or meaning requires a new major version.
- A producer may dual-publish v1 and a future v2 for one deprecation window.

See [migration](docs/migration.md), [Develocity projection](docs/develocity-projection.md),
and [query examples](docs/querying.md). See [releasing](docs/releasing.md) for
artifact versioning and the local Maven Central publication workflow.

## License

MIT
