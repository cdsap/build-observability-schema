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


def validate_observation(
    observation: dict[str, Any],
    source: str,
    metrics: dict[str, dict[str, Any]],
    attributes: dict[str, dict[str, Any]],
    scopes: set[str],
) -> None:
    require(observation.get("schemaVersion") == "1.0.0", f"{source}: invalid schemaVersion")
    producer = observation.get("producer")
    require(isinstance(producer, dict), f"{source}: producer must be an object")
    require(isinstance(producer.get("name"), str) and producer["name"], f"{source}: producer.name is required")
    require(isinstance(producer.get("version"), str) and producer["version"], f"{source}: producer.version is required")

    scope = observation.get("scope")
    require(scope in scopes, f"{source}: unregistered scope {scope!r}")
    require(observation.get("aggregationScope") in {"entity", "task", "project", "build"},
            f"{source}: invalid aggregationScope")

    actual_attributes = observation.get("attributes")
    require(isinstance(actual_attributes, dict), f"{source}: attributes must be an object")
    for name, value in actual_attributes.items():
        require(NAME.fullmatch(name) is not None, f"{source}: invalid attribute name {name!r}")
        require(name in attributes, f"{source}: unregistered attribute {name!r}")
        declared = attributes[name]
        declared_type = declared["type"]
        valid_type = {
            "string": isinstance(value, str),
            "integer": isinstance(value, int) and not isinstance(value, bool),
            "number": is_number(value),
            "boolean": isinstance(value, bool),
        }.get(declared_type, False)
        require(valid_type, f"{source}: attribute {name!r} must be {declared_type}")
        if "values" in declared:
            require(value in declared["values"], f"{source}: invalid value {value!r} for {name!r}")

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


def main() -> int:
    for path in ROOT.rglob("*.json"):
        load(path)

    registry = load(ROOT / "registry" / "semantic-conventions.json")
    metrics = {item["name"]: item for item in registry["metrics"]}
    attributes = {item["name"]: item for item in registry["attributes"]}
    scopes = {item["name"] for item in registry["scopes"]}

    require(len(metrics) == len(registry["metrics"]), "registry: duplicate metric name")
    require(len(attributes) == len(registry["attributes"]), "registry: duplicate attribute name")
    require(len(scopes) == len(registry["scopes"]), "registry: duplicate scope name")

    observation_count = 0
    for path in sorted((ROOT / "examples").glob("*.json")):
        report = load(path)
        require(report.get("schemaVersion") == "1.0.0", f"{path.name}: invalid report schemaVersion")
        require(isinstance(report.get("resource"), dict), f"{path.name}: resource must be an object")
        for attribute in report["resource"]:
            require(attribute in attributes, f"{path.name}: unregistered resource attribute {attribute!r}")
        observations = report.get("observations")
        require(isinstance(observations, list) and observations, f"{path.name}: observations must be non-empty")
        for index, observation in enumerate(observations):
            validate_observation(observation, f"{path.name}.observations[{index}]", metrics, attributes, scopes)
            observation_count += 1

    indexes = load(ROOT / "registry" / "develocity-indexes.json")["indexes"]
    index_names = {item["name"] for item in indexes}
    require(len(index_names) == len(indexes), "develocity index registry: duplicate name")
    for item in indexes:
        require(item["metric"] in metrics, f"develocity index: unknown metric {item['metric']!r}")
        require(item["scope"] in scopes, f"develocity index: unknown scope {item['scope']!r}")
        require(item["aggregation"] in metrics[item["metric"]]["allowedAggregations"],
                f"develocity index: aggregation not allowed for {item['metric']!r}")

    projection = load(ROOT / "develocity" / "custom-values.json")
    for index, item in enumerate(projection["customValues"]):
        name = item["name"]
        value = item["value"]
        if name == "gbos.v1.observation":
            validate_observation(json.loads(value), f"customValues[{index}]", metrics, attributes, scopes)
        else:
            require(name in index_names, f"customValues[{index}]: undeclared index {name!r}")
            require(is_number(float(value)), f"customValues[{index}]: index value is not numeric")

    print(f"Validated {observation_count} report observations and {len(projection['customValues'])} custom values.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValidationError as exc:
        print(f"validation failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
