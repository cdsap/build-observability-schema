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

The same object is used in both output paths:

| Sink | Representation |
|---|---|
| JSON file | A report envelope containing `observations[]` |
| Terminal / streaming | One compact observation per line (NDJSON) |
| Develocity | Custom value name `gbos.v1.observation`; value is the compact observation JSON |
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
6. **Every observation identifies its producer and schema version.** It remains
   meaningful when copied out of a report or Build Scan.
7. **Partial data is visible.** Set `partial`, `droppedObservations`, and/or
   `diagnostics`; never represent missing measurements as zero.

These choices follow OpenTelemetry's hierarchy and attribute guidance and its
UCUM base-unit recommendations, while keeping the transport deliberately much
smaller than OTLP.

## Repository layout

```text
schema/                         JSON Schema 2020-12 contracts
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

For full JSON Schema validation, install `check-jsonschema` and run:

```bash
check-jsonschema --check-metaschema schema/*.json
check-jsonschema --schemafile schema/report.schema.json examples/*.json
check-jsonschema --schemafile schema/develocity-projection.schema.json develocity/*.json
```

## Versioning

- `schemaVersion` uses semantic versioning.
- Develocity keys include only the major version: `gbos.v1.*`.
- Additive registry entries and optional fields are minor changes.
- Removing/renaming a field, metric, unit, or meaning requires a new major version.
- A producer may dual-publish v1 and a future v2 for one deprecation window.

See [migration](docs/migration.md), [Develocity projection](docs/develocity-projection.md),
and [query examples](docs/querying.md).

## License

MIT
