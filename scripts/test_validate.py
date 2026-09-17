#!/usr/bin/env python3
"""Focused tests for shared observation-header validation."""

import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from validate import ValidationError, load, validate_observation_batch, validate_registry  # noqa: E402


class ObservationBatchValidationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        root = Path(__file__).resolve().parents[1]
        cls.metrics, cls.attributes, cls.scopes = validate_registry(
            load(root / "registry" / "semantic-conventions.json")
        )
        cls.batch = {
            "schemaVersion": "1.0.0",
            "producer": {"name": "info-test-process", "version": "2.1.0"},
            "observations": [
                {
                    "scope": "jvm.process",
                    "aggregationScope": "build",
                    "attributes": {"jvm.process.role": "test-worker"},
                    "measurements": [
                        {"name": "jvm.process.cpu.time", "value": 5.38, "unit": "s", "aggregation": "sum"}
                    ],
                }
            ],
        }

    def test_accepts_observations_with_shared_metadata(self):
        observations = validate_observation_batch(
            self.batch, "batch", self.metrics, self.attributes, self.scopes
        )
        self.assertEqual(1, len(observations))

    def test_rejects_child_schema_version(self):
        invalid = copy.deepcopy(self.batch)
        invalid["observations"][0]["schemaVersion"] = "1.0.0"
        with self.assertRaisesRegex(ValidationError, "belongs in the batch header"):
            validate_observation_batch(invalid, "batch", self.metrics, self.attributes, self.scopes)

    def test_rejects_child_producer(self):
        invalid = copy.deepcopy(self.batch)
        invalid["observations"][0]["producer"] = self.batch["producer"]
        with self.assertRaisesRegex(ValidationError, "belongs in the batch header"):
            validate_observation_batch(invalid, "batch", self.metrics, self.attributes, self.scopes)


if __name__ == "__main__":
    unittest.main()
