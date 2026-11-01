"""Runtime compatibility contract for the shared sidecar dependency pins."""

from pathlib import Path
import re
import unittest


class SharedBaseRuntimeTest(unittest.TestCase):
    def test_both_targets_keep_the_supported_python_runtime(self):
        dockerfile = Path(__file__).with_name("Dockerfile").read_text()
        stages = re.findall(r"^FROM (\S+) AS (\S+)$", dockerfile, re.MULTILINE)

        # The pinned Pydantic core has no CPython 3.15 wheels. A runtime bump
        # needs dependency and multi-architecture build validation first.
        self.assertRegex(
            {name: image for image, name in stages}["python"],
            r"^python:3\.12-slim-bookworm@sha256:[0-9a-f]{64}$",
        )
        self.assertIn(("python", "chromium"), stages)
