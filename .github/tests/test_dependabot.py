"""Keep dependency-update coverage aligned with the repository manifests."""

from pathlib import Path
import re
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[2]
# Every filename Docker Compose loads by default, plus its override variants.
COMPOSE_FILE = re.compile(r"(docker-)?compose(\..+)?\.ya?ml")


class DependabotCoverageTest(unittest.TestCase):
    def test_all_dependency_manifests_have_a_weekly_update_schedule(self):
        config = yaml.safe_load((ROOT / ".github/dependabot.yml").read_text())
        self.assertEqual(config["version"], 2)
        covered = {}
        for update in config["updates"]:
            self.assertEqual(update["schedule"]["interval"], "weekly")
            directories = update.get("directories", [update.get("directory")])
            for directory in directories:
                for path in ([ROOT] if directory == "/" else ROOT.glob(directory.lstrip("/"))):
                    covered.setdefault(update["package-ecosystem"], set()).add(path.resolve())

        expected = {
            "github-actions": {ROOT},
            "maven": {path.parent for path in ROOT.glob("*/pom.xml")},
            "bun": {path.parent for path in ROOT.glob("*/bun.lock")},
            "pip": {path.parent for path in (ROOT / "services").glob("*/requirements*.txt")},
            "docker-compose": {path.parent for path in ROOT.rglob("*.y*ml")
                               if COMPOSE_FILE.fullmatch(path.name) and "node_modules" not in path.parts},
            "docker": {path.parent for path in ROOT.glob("**/Dockerfile*")
                       if "node_modules" not in path.parts and ".venv" not in path.parts},
        }
        for ecosystem, directories in expected.items():
            with self.subTest(ecosystem=ecosystem):
                self.assertTrue(directories)
                self.assertLessEqual(directories, covered.get(ecosystem, set()))


if __name__ == "__main__":
    unittest.main()
