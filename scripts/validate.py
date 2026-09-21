#!/usr/bin/env python3
"""Validate GBOS examples and registry semantics without third-party packages."""

from __future__ import annotations

import json
import math
import re
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
NAME = re.compile(r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*$")
INDEX = re.compile(
    r"^gbos\.v1\.index\.(?P<producer>[a-z0-9_]+)\.(?P<metric>[a-z][a-z0-9_.]*)\.(?P<aggregation>last|min|max|sum|count)$"
)


class ValidationError(Exception):
    pass


def load(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValidationError(f"{path.relative_to(ROOT)}: {exc}") from exc


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValidationError(message)


def is_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def matches_declared_type(value: Any, declared_type: str) -> bool:
    return {
        "string": isinstance(value, str),
        "integer": isinstance(value, int) and not isinstance(value, bool),
        "number": is_number(value),
        "boolean": isinstance(value, bool),
    }.get(declared_type, False)


def producer_slug(name: str) -> str:
    return name.replace("-", "_").replace(".", "_")


def validate_attribute_value(
    name: str,
    value: Any,
    attributes: dict[str, dict[str, Any]],
    source: str,
) -> None:
    require(NAME.fullmatch(name) is not None, f"{source}: invalid attribute name {name!r}")
    require(name in attributes, f"{source}: unregistered attribute {name!r}")
    declared = attributes[name]
    declared_type = declared["type"]
    require(matches_declared_type(value, declared_type), f"{source}: attribute {name!r} must be {declared_type}")
    if "values" in declared:
        require(value in declared["values"], f"{source}: invalid value {value!r} for {name!r}")


def parse_observation_json(value: str, source: str) -> dict[str, Any]:
    try:
        observation = json.loads(value)
    except json.JSONDecodeError as exc:
        raise ValidationError(f"{source}: observation value is not valid JSON: {exc}") from exc
    require(isinstance(observation, dict), f"{source}: observation value must encode an object")
    return observation


def parse_numeric_string(value: Any, source: str) -> float:
    require(isinstance(value, str), f"{source}: index value must be a string")
    try:
        number = float(value)
    except ValueError as exc:
        raise ValidationError(f"{source}: index value is not numeric") from exc
    require(is_number(number), f"{source}: index value must be finite")
    return number


def index_identity(name: str) -> tuple[str, str, str]:
    match = INDEX.fullmatch(name)
    require(match is not None, f"{name!r}: invalid index name")
    return match.group("producer"), match.group("metric"), match.group("aggregation")


def validate_observation(
    observation: dict[str, Any],
    source: str,
    metrics: dict[str, dict[str, Any]],
    attributes: dict[str, dict[str, Any]],
    scopes: set[str],
    inherited_metadata: dict[str, Any] | None = None,
) -> None:
    if inherited_metadata is None:
        require(observation.get("schemaVersion") == "1.0.0", f"{source}: invalid schemaVersion")
        producer = observation.get("producer")
        require(isinstance(producer, dict), f"{source}: producer must be an object")
    else:
        require("schemaVersion" not in observation, f"{source}: schemaVersion belongs in the batch header")
        require("producer" not in observation, f"{source}: producer belongs in the batch header")
        producer = inherited_metadata["producer"]
    require(isinstance(producer.get("name"), str) and producer["name"], f"{source}: producer.name is required")
    require(isinstance(producer.get("version"), str) and producer["version"], f"{source}: producer.version is required")

    scope = observation.get("scope")
    require(scope in scopes, f"{source}: unregistered scope {scope!r}")
    require(observation.get("aggregationScope") in {"entity", "task", "project", "build"},
            f"{source}: invalid aggregationScope")

    actual_attributes = observation.get("attributes")
    require(isinstance(actual_attributes, dict), f"{source}: attributes must be an object")
    for name, value in actual_attributes.items():
        validate_attribute_value(name, value, attributes, source)

    measurements = observation.get("measurements", [])
    histograms = observation.get("histograms", [])
    require(bool(measurements or histograms), f"{source}: at least one measurement or histogram is required")
    require(isinstance(measurements, list), f"{source}: measurements must be an array")
    require(isinstance(histograms, list), f"{source}: histograms must be an array")

    seen: set[tuple[str, str]] = set()
    for index, measurement in enumerate(measurements):
        where = f"{source}.measurements[{index}]"
        require(isinstance(measurement, dict), f"{where}: must be an object")
        name = measurement.get("name")
        aggregation = measurement.get("aggregation")
        require(name in metrics, f"{where}: unregistered metric {name!r}")
        definition = metrics[name]
        require(definition["type"] != "histogram", f"{where}: histogram metric used as scalar")
        require(measurement.get("unit") == definition["unit"],
                f"{where}: unit must be {definition['unit']!r}")
        require(aggregation in definition["allowedAggregations"],
                f"{where}: aggregation {aggregation!r} is not allowed")
        require(is_number(measurement.get("value")), f"{where}: value must be a finite number")
        identity = (name, aggregation)
        require(identity not in seen, f"{where}: duplicate metric/aggregation {identity}")
        seen.add(identity)

    for index, histogram in enumerate(histograms):
        where = f"{source}.histograms[{index}]"
        require(isinstance(histogram, dict), f"{where}: must be an object")
        name = histogram.get("name")
        require(name in metrics, f"{where}: unregistered metric {name!r}")
        definition = metrics[name]
        require(definition["type"] == "histogram", f"{where}: scalar metric used as histogram")
        require(histogram.get("unit") == definition["unit"],
                f"{where}: unit must be {definition['unit']!r}")
        aggregation = histogram.get("aggregation")
        require(aggregation in definition["allowedAggregations"],
                f"{where}: aggregation {aggregation!r} is not allowed")
        buckets = histogram.get("buckets")
        require(isinstance(buckets, list) and buckets, f"{where}: buckets must be non-empty")
        previous_gte: float | None = None
        for bucket_index, bucket in enumerate(buckets):
            bucket_where = f"{where}.buckets[{bucket_index}]"
            require(isinstance(bucket, dict), f"{bucket_where}: must be an object")
            gte = bucket.get("gte")
            lt = bucket.get("lt")
            count = bucket.get("count")
            require(is_number(gte), f"{bucket_where}: gte must be a finite number")
            require(lt is None or is_number(lt), f"{bucket_where}: lt must be a finite number")
            require(lt is None or lt > gte, f"{bucket_where}: lt must be greater than gte")
            require(isinstance(count, int) and not isinstance(count, bool) and count >= 0,
                    f"{bucket_where}: count must be a non-negative integer")
            require(previous_gte is None or gte >= previous_gte,
                    f"{bucket_where}: buckets must be ordered")
            previous_gte = gte

    dropped = observation.get("droppedObservations", 0)
    require(isinstance(dropped, int) and dropped >= 0, f"{source}: invalid droppedObservations")
    require(dropped == 0 or observation.get("partial") is True,
            f"{source}: droppedObservations requires partial=true")


def validate_observation_batch(
    batch: dict[str, Any],
    source: str,
    metrics: dict[str, dict[str, Any]],
    attributes: dict[str, dict[str, Any]],
    scopes: set[str],
) -> list[dict[str, Any]]:
    require(batch.get("schemaVersion") == "1.0.0", f"{source}: invalid schemaVersion")
    producer = batch.get("producer")
    require(isinstance(producer, dict), f"{source}: producer must be an object")
    require(isinstance(producer.get("name"), str) and producer["name"], f"{source}: producer.name is required")
    require(isinstance(producer.get("version"), str) and producer["version"], f"{source}: producer.version is required")
    observations = batch.get("observations")
    require(isinstance(observations, list) and observations, f"{source}: observations must be non-empty")
    metadata = {"schemaVersion": batch["schemaVersion"], "producer": producer}
    for index, observation in enumerate(observations):
        require(isinstance(observation, dict), f"{source}.observations[{index}]: must be an object")
        validate_observation(observation, f"{source}.observations[{index}]", metrics, attributes, scopes, metadata)
    return observations


def validate_registry(registry: dict[str, Any]) -> tuple[dict[str, dict[str, Any]], dict[str, dict[str, Any]], set[str]]:
    require(registry.get("schemaVersion") == "1.0.0", "registry: invalid schemaVersion")
    require(isinstance(registry.get("scopes"), list), "registry: scopes must be an array")
    require(isinstance(registry.get("metrics"), list), "registry: metrics must be an array")
    require(isinstance(registry.get("attributes"), list), "registry: attributes must be an array")

    metrics: dict[str, dict[str, Any]] = {}
    attributes: dict[str, dict[str, Any]] = {}
    scopes: set[str] = set()

    for index, scope in enumerate(registry["scopes"]):
        where = f"registry.scopes[{index}]"
        require(isinstance(scope, dict), f"{where}: must be an object")
        name = scope.get("name")
        require(isinstance(name, str) and NAME.fullmatch(name) is not None, f"{where}: invalid name")
        require(name not in scopes, f"registry: duplicate scope name {name!r}")
        scopes.add(name)

    for index, metric in enumerate(registry["metrics"]):
        where = f"registry.metrics[{index}]"
        require(isinstance(metric, dict), f"{where}: must be an object")
        name = metric.get("name")
        require(isinstance(name, str) and NAME.fullmatch(name) is not None, f"{where}: invalid name")
        require(name not in metrics, f"registry: duplicate metric name {name!r}")
        require(isinstance(metric.get("unit"), str) and metric["unit"], f"{where}: unit is required")
        require(metric.get("type") in {"counter", "gauge", "histogram"}, f"{where}: invalid type")
        allowed = metric.get("allowedAggregations")
        require(isinstance(allowed, list) and allowed, f"{where}: allowedAggregations must be non-empty")
        for aggregation in allowed:
            require(aggregation in {"last", "min", "max", "sum", "count"}, f"{where}: invalid aggregation")
        metrics[name] = metric

    for index, attribute in enumerate(registry["attributes"]):
        where = f"registry.attributes[{index}]"
        require(isinstance(attribute, dict), f"{where}: must be an object")
        name = attribute.get("name")
        require(isinstance(name, str) and NAME.fullmatch(name) is not None, f"{where}: invalid name")
        require(name not in attributes, f"registry: duplicate attribute name {name!r}")
        require(attribute.get("type") in {"string", "integer", "number", "boolean"}, f"{where}: invalid type")
        require(attribute.get("cardinality") in {"low", "medium", "high"}, f"{where}: invalid cardinality")
        values = attribute.get("values")
        require(values is None or isinstance(values, list), f"{where}: values must be an array")
        if values is not None:
            for value_index, value in enumerate(values):
                require(matches_declared_type(value, attribute["type"]),
                        f"{where}.values[{value_index}]: value must be {attribute['type']}")
        attributes[name] = attribute

    return metrics, attributes, scopes


def validate_index_registry(
    registry: dict[str, Any],
    metrics: dict[str, dict[str, Any]],
    scopes: set[str],
) -> dict[str, dict[str, Any]]:
    require(registry.get("schemaVersion") == "1.0.0", "develocity index registry: invalid schemaVersion")
    indexes = registry.get("indexes")
    require(isinstance(indexes, list), "develocity index registry: indexes must be an array")
    index_definitions: dict[str, dict[str, Any]] = {}
    for index, item in enumerate(indexes):
        where = f"develocity index registry.indexes[{index}]"
        require(isinstance(item, dict), f"{where}: must be an object")
        name = item.get("name")
        producer = item.get("producer")
        metric = item.get("metric")
        scope = item.get("scope")
        aggregation = item.get("aggregation")
        require(isinstance(name, str), f"{where}: name is required")
        require(name not in index_definitions, f"develocity index registry: duplicate name {name!r}")
        index_definitions[name] = item
        match = INDEX.fullmatch(name)
        require(match is not None, f"{where}: invalid index name")
        require(isinstance(producer, str) and producer, f"{where}: producer is required")
        require(match.group("producer") == producer_slug(producer), f"{where}: producer slug does not match producer")
        require(metric in metrics, f"{where}: unknown metric {metric!r}")
        require(match.group("metric") == metric, f"{where}: metric does not match name")
        require(scope in scopes, f"{where}: unknown scope {scope!r}")
        require(aggregation in metrics[metric]["allowedAggregations"], f"{where}: aggregation not allowed for {metric!r}")
        require(match.group("aggregation") == aggregation, f"{where}: aggregation does not match name")
        require(item.get("aggregationScope") == "build", f"{where}: aggregationScope must be 'build'")
    return index_definitions


def main() -> int:
    for path in ROOT.rglob("*.json"):
        load(path)

    metrics, attributes, scopes = validate_registry(load(ROOT / "registry" / "semantic-conventions.json"))

    observation_count = 0
    for path in sorted((ROOT / "examples").glob("*.json")):
        report = load(path)
        require(report.get("schemaVersion") == "1.0.0", f"{path.name}: invalid report schemaVersion")
        require(isinstance(report.get("resource"), dict), f"{path.name}: resource must be an object")
        for attribute, value in report["resource"].items():
            validate_attribute_value(attribute, value, attributes, path.name)
        observations = report.get("observations")
        if observations is not None:
            require(isinstance(observations, list) and observations, f"{path.name}: observations must be non-empty")
            for index, observation in enumerate(observations):
                validate_observation(observation, f"{path.name}.observations[{index}]", metrics, attributes, scopes)
                observation_count += 1
        batches = report.get("observationBatches")
        if batches is not None:
            require(isinstance(batches, list) and batches, f"{path.name}: observationBatches must be non-empty")
            for index, batch in enumerate(batches):
                observations = validate_observation_batch(
                    batch, f"{path.name}.observationBatches[{index}]", metrics, attributes, scopes
                )
                observation_count += len(observations)

    index_definitions = validate_index_registry(load(ROOT / "registry" / "develocity-indexes.json"), metrics, scopes)

    projection = load(ROOT / "develocity" / "custom-values.json")
    require(isinstance(projection.get("customValues"), list), "customValues: customValues must be an array")
    build_measurements: dict[tuple[str, str, str], float] = {}
    projected_indexes: list[tuple[int, str, Any]] = []
    shared_headers: dict[str, str] = {}
    for index, item in enumerate(projection["customValues"]):
        require(isinstance(item, dict), f"customValues[{index}]: must be an object")
        name = item.get("name")
        value = item.get("value")
        require(isinstance(name, str), f"customValues[{index}]: name is required")
        if name in {"gbos.schema", "gbos.version", "gbos.producer"}:
            require(isinstance(value, str) and value, f"customValues[{index}]: shared header must be a non-empty string")
            require(name not in shared_headers, f"customValues[{index}]: duplicate shared header {name!r}")
            shared_headers[name] = value
        elif name in {"gbos.v1.observation", "gbos.v1.observations"}:
            require(isinstance(value, str), f"customValues[{index}]: observation value must be a string")
            parsed = parse_observation_json(value, f"customValues[{index}]")
            if name == "gbos.v1.observation":
                if shared_headers:
                    require(set(shared_headers) == {"gbos.schema", "gbos.version", "gbos.producer"},
                            "shared GBOS headers must include schema, version, and producer")
                    require(parsed.get("schemaVersion") is None and parsed.get("producer") is None,
                            f"customValues[{index}]: shared-header observation must omit schemaVersion and producer")
                    validate_observation(
                        parsed, f"customValues[{index}]", metrics, attributes, scopes,
                        inherited_metadata={"producer": {"name": shared_headers["gbos.producer"],
                                                           "version": shared_headers["gbos.version"]}},
                    )
                    observations_with_producers = [(shared_headers["gbos.producer"], parsed)]
                else:
                    validate_observation(parsed, f"customValues[{index}]", metrics, attributes, scopes)
                    observations_with_producers = [(parsed["producer"]["name"], parsed)]
            else:
                observations = validate_observation_batch(
                    parsed, f"customValues[{index}]", metrics, attributes, scopes
                )
                observations_with_producers = [(parsed["producer"]["name"], observation) for observation in observations]
            for producer_name, observation in observations_with_producers:
                if observation["aggregationScope"] == "build":
                    producer = producer_slug(producer_name)
                    for measurement in observation.get("measurements", []):
                        build_measurements[(producer, measurement["name"], measurement["aggregation"])] = measurement["value"]
        else:
            projected_indexes.append((index, name, value))

    for index, name, value in projected_indexes:
        require(name in index_definitions, f"customValues[{index}]: undeclared index {name!r}")
        number = parse_numeric_string(value, f"customValues[{index}]")
        identity = index_identity(name)
        require(identity in build_measurements,
                f"customValues[{index}]: index has no matching build-level observation measurement")
        require(math.isclose(number, build_measurements[identity], rel_tol=0, abs_tol=1e-12),
                f"customValues[{index}]: index value does not match build-level observation measurement")

    if any(item["name"] == "gbos.v1.observation" for item in projection["customValues"]):
        require(set(shared_headers) == {"gbos.schema", "gbos.version", "gbos.producer"},
                "gbos.v1.observation values require exactly one shared schema, version, and producer header")
        require(shared_headers["gbos.schema"] == "1.0.0", "gbos.schema must be 1.0.0")

    print(f"Validated {observation_count} report observations and {len(projection['customValues'])} custom values.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValidationError as exc:
        print(f"validation failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
