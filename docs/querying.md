# Querying

## jq

List every process and its metrics:

```bash
jq -r '
  .observations[]
  | select(.scope == "jvm.process" and .aggregationScope == "entity")
  | .attributes as $a
  | .measurements[]
  | [$a["jvm.process.role"], $a["process.pid"], .name, .value, .unit, .aggregation]
  | @tsv
' examples/test-process.json
```

Get peak heap in GiB without changing the stored base unit:

```bash
jq '
  [.observations[].measurements[]
   | select(.name == "jvm.process.memory.heap.peak")
   | .value / 1073741824]
' examples/test-process.json
```

Select artifact sizes:

```bash
jq -r '
  .observations[]
  | select(.scope == "build.artifact")
  | .attributes as $a
  | .measurements[]
  | select(.name == "build.artifact.size")
  | [$a["gradle.project.path"], $a["artifact.type"], $a["artifact.name"], .value]
  | @tsv
' examples/android-artifact.json
```

Convert a report to streamable NDJSON:

```bash
jq -c '.observations[]' examples/test-process.json > observations.ndjson
```

The same filter works on NDJSON by replacing `.observations[]` with `.`.

## Develocity API + jq

Extract canonical observations from the Gradle attributes model:

```bash
curl --location -G "https://$DV_URL/api/builds" \
  --data-urlencode 'models=gradle-attributes' \
  --data-urlencode 'reverse=true' \
  --data-urlencode 'maxBuilds=100' \
  --header "Authorization: Bearer $DV_TOKEN" \
| jq '
    .[]
    | .models.gradleAttributes.model.values[]
    | select(.name == "gbos.v1.observation")
    | .value
    | fromjson
  '
```

Find all test workers with peak heap above 1 GiB:

```bash
jq -r '
  select(.scope == "jvm.process")
  | select(.attributes["jvm.process.role"] == "test-worker")
  | .attributes as $a
  | .measurements[]
  | select(.name == "jvm.process.memory.heap.peak" and .value > 1073741824)
  | [$a["gradle.task.path"], $a["process.pid"], .value]
  | @tsv
'
```

## Develocity Analytics / Trino-style SQL

Use scalar indexes for common build-level thresholds:

```sql
SELECT build_id, CAST(value AS DOUBLE) AS cpu_seconds
FROM gradle_attributes_custom_values
WHERE name = 'gbos.v1.index.info_test_process.jvm.process.cpu.time.sum'
  AND CAST(value AS DOUBLE) > 300;
```

Parse canonical observations when attributes or per-entity detail are needed:

```sql
WITH observations AS (
  SELECT build_id, JSON_PARSE(value) AS observation
  FROM gradle_attributes_custom_values
  WHERE name = 'gbos.v1.observation'
), exploded AS (
  SELECT
    build_id,
    CAST(JSON_EXTRACT(observation, '$.attributes') AS MAP(VARCHAR, JSON)) AS attributes,
    measurement
  FROM observations
  CROSS JOIN UNNEST(
    CAST(JSON_EXTRACT(observation, '$.measurements') AS ARRAY(JSON))
  ) AS t(measurement)
)
SELECT
  build_id,
  JSON_EXTRACT_SCALAR(attributes['gradle.task.path'], '$') AS task,
  JSON_EXTRACT_SCALAR(attributes['process.pid'], '$') AS pid,
  CAST(JSON_EXTRACT_SCALAR(measurement, '$.value') AS DOUBLE) AS cpu_seconds
FROM exploded
WHERE JSON_EXTRACT_SCALAR(measurement, '$.name') = 'jvm.process.cpu.time';
```

JSON function spelling can differ by the Develocity Analytics engine/version;
the data model intentionally requires only a fixed custom-value name and standard
JSON extraction.
