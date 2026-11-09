"""Keep dependency-update coverage aligned with the repository manifests."""

from fnmatch import fnmatch
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
            "bun": {path.parent for path in ROOT.rglob("bun.lock") if "node_modules" not in path.parts},
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

    def test_postgres_major_bumps_are_ignored_until_the_planned_migration(self):
        # PostgreSQL 18 moves PGDATA and cannot open a 16 data directory (#508).
        config = yaml.safe_load((ROOT / ".github/dependabot.yml").read_text())
        for ecosystem in ("docker", "docker-compose"):
            with self.subTest(ecosystem=ecosystem):
                update = next(u for u in config["updates"] if u["package-ecosystem"] == ecosystem)
                self.assertIn("/docker", update["directories"])
                ignored = [i for i in update.get("ignore", []) if i["dependency-name"] == "postgres"]
                self.assertEqual(len(ignored), 1)
                self.assertEqual(ignored[0]["update-types"], ["version-update:semver-major"])

    def test_typescript_major_bumps_are_ignored_until_typescript_eslint_supports_it(self):
        # typescript-eslint peers on typescript <6.1.0, so TypeScript 7 cannot be linted (#526).
        config = yaml.safe_load((ROOT / ".github/dependabot.yml").read_text())
        update = next(u for u in config["updates"]
                      if u["package-ecosystem"] == "bun" and u["directory"] == "/frontend")
        ignored = [i for i in update.get("ignore", []) if i["dependency-name"] == "typescript"]
        self.assertEqual(len(ignored), 1)
        self.assertEqual(ignored[0]["update-types"], ["version-update:semver-major"])

    def test_codeql_actions_share_one_revision_and_one_update(self):
        # analyze refuses a config written by another init release (#491, #492).
        pins = {}
        for path in (ROOT / ".github/workflows").glob("*.y*ml"):
            for job in yaml.safe_load(path.read_text())["jobs"].values():
                for step in job.get("steps", []):
                    name, _, revision = step.get("uses", "").partition("@")
                    if name.startswith("github/codeql-action/"):
                        pins.setdefault(name, set()).add(revision)
        self.assertLessEqual({"github/codeql-action/init", "github/codeql-action/analyze"}, pins.keys())
        revisions = set().union(*pins.values())
        self.assertEqual(len(revisions), 1, pins)

        config = yaml.safe_load((ROOT / ".github/dependabot.yml").read_text())
        actions = next(u for u in config["updates"] if u["package-ecosystem"] == "github-actions")
        grouped = [group for group in actions.get("groups", {}).values()
                   if all(any(fnmatch(name, p) for p in group.get("patterns", [])) for name in pins)]
        self.assertTrue(grouped, f"{sorted(pins)} must share one Dependabot group")


if __name__ == "__main__":
    unittest.main()
