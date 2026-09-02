# Producer adoption checklist

- [ ] Producer name is stable, lowercase, and declared in the release notes.
- [ ] Every output adapter consumes the same in-memory observation model.
- [ ] Metric, scope, attribute, unit, and aggregation exist in the registry.
- [ ] PIDs, file names, paths, task paths, and variants are attributes, not keys.
- [ ] Raw numeric precision is retained until serialization.
- [ ] Durations are seconds and memory/storage is bytes.
- [ ] Missing values are omitted and explained with diagnostics.
- [ ] File output validates against `report.schema.json`.
- [ ] NDJSON writes exactly one compact observation per line.
- [ ] Build Scan JSON is compact and below the configured size budget.
- [ ] Only allowlisted build-level scalar indexes are emitted.
- [ ] Truncation is deterministic and reported as partial data.
- [ ] Legacy and GBOS outputs are dual-published for a documented transition.
- [ ] Tests assert semantic values, not only serialized snapshots.
