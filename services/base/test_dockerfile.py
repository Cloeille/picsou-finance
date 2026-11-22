"""Runtime compatibility contract for the shared sidecar dependency pins."""

from pathlib import Path
import re
import unittest


class SharedBaseRuntimeTest(unittest.TestCase):
    def test_both_targets_keep_the_supported_python_runtime(self):
        dockerfile = Path(__file__).with_name("Dockerfile").read_text()
        stages = re.findall(r"^FROM (\S+) AS (\S+)$", dockerfile, re.MULTILINE)

        # For CPython 3.15, orjson (via camoufox) has only manylinux_2_39
        # wheels, which bookworm's glibc 2.36 cannot install, and PyYAML has
        # no wheel, so a source build lacks the CLoader camoufox imports.
        # A runtime bump needs dependency and multi-architecture build
        # validation first; .github/dependabot.yml ignores python >= 3.13.
        self.assertRegex(
            {name: image for image, name in stages}["python"],
            r"^python:3\.12-slim-bookworm@sha256:[0-9a-f]{64}$",
        )
        self.assertIn(("python", "chromium"), stages)

    def test_chromium_target_installs_the_driver_node_runtime(self):
        # The Node.js binary in the Playwright driver (1.65.1 and later) links
        # libatomic.so.1, which the slim image lacks: without it the
        # `playwright` CLI and every launch exit with "error while loading
        # shared libraries".
        dockerfile = Path(__file__).with_name("Dockerfile").read_text()
        stages = dict(
            re.findall(r"^FROM \S+ AS (\S+)$\n(.*?)(?=^FROM |\Z)", dockerfile, re.MULTILINE | re.DOTALL)
        )
        self.assertRegex(stages["chromium"], r"apt-get install[^&]*\blibatomic1\b")
