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
            "cargo": {path.parent for path in ROOT.rglob("Cargo.lock") if "target" not in path.parts},
            "bun": {path.parent for path in ROOT.rglob("bun.lock") if "node_modules" not in path.parts},
            "pip": {path.parent for path in (ROOT / "services").rglob("requirements*.txt")
                    if "node_modules" not in path.parts and ".venv" not in path.parts},
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
        groups = actions.get("groups", {})

        def matches(group, dep):
            included = any(fnmatch(dep, p) for p in group.get("patterns", []))
            excluded = any(fnmatch(dep, p) for p in group.get("exclude-patterns", []))
            return included and not excluded

        # Dependabot assigns a dependency to the first matching group.
        # Every CodeQL action must resolve to the same group, and that group
        # must include major bumps (no update-types excluding major) so init
        # and analyze never drift on a major release.
        assigned = {
            dep: next((name for name, group in groups.items() if matches(group, dep)), None)
            for dep in pins
        }
        self.assertEqual(len(set(assigned.values())), 1, assigned)
        group_name = next(iter(assigned.values()))
        self.assertIsNotNone(group_name, f"{sorted(pins)} must share one Dependabot group")
        codeql_group = groups[group_name]
        update_types = codeql_group.get("update-types")
        if update_types is not None:
            self.assertIn("major", update_types, f"{group_name} must keep major CodeQL bumps together")

        # Any catch-all group must also exclude CodeQL actions explicitly so
        # reordering groups cannot split CodeQL major bumps.
        for other_name, other_group in groups.items():
            if other_name != group_name and "*" in other_group.get("patterns", []):
                for dep in pins:
                    self.assertFalse(
                        matches(other_group, dep),
                        f"Catch-all group {other_name!r} must exclude {dep}",
                    )

    def test_every_update_entry_groups_minor_and_patch_version_updates_only(self):
        config = yaml.safe_load((ROOT / ".github/dependabot.yml").read_text(encoding="utf-8"))
        self.assertNotIn(
            "multi-ecosystem-groups",
            config,
            "Never group updates across unrelated ecosystems or directories",
        )
        valid_update_types = {"major", "minor", "patch"}
        for update in config["updates"]:
            ecosystem = update["package-ecosystem"]
            scope = update.get("directory") or update.get("directories")
            with self.subTest(ecosystem=ecosystem, scope=scope):
                self.assertNotIn("multi-ecosystem-group", update)
                groups = update.get("groups", {})
                self.assertTrue(groups, "Every update entry must define a minor/patch group")

                for group_name, group in groups.items():
                    self.assertEqual(
                        group.get("applies-to"),
                        "version-updates",
                        f"{group_name} must set applies-to: version-updates so security updates stay separate",
                    )
                    self.assertNotIn(
                        "group-by",
                        group,
                        f"{group_name} must not use group-by across directories",
                    )
                    if "update-types" in group:
                        self.assertLessEqual(set(group["update-types"]), valid_update_types)

                catch_all = [
                    (name, group)
                    for name, group in groups.items()
                    if "*" in group.get("patterns", [])
                ]
                self.assertEqual(
                    len(catch_all),
                    1,
                    f"Expected exactly one catch-all ('*') group, found {[n for n, _ in catch_all]}",
                )
                _, group = catch_all[0]
                self.assertEqual(
                    set(group.get("update-types", [])),
                    {"minor", "patch"},
                    "Catch-all group must bundle minor and patch updates only (major updates stay individual)",
                )

    def test_groups_on_shared_directories_have_distinct_names(self):
        config = yaml.safe_load((ROOT / ".github/dependabot.yml").read_text(encoding="utf-8"))
        seen_by_dir = {}
        for update in config["updates"]:
            directories = update.get("directories", [update.get("directory")])
            for directory in directories:
                resolved_dirs = (
                    [ROOT.resolve()]
                    if directory == "/"
                    else sorted(p.resolve() for p in ROOT.glob(directory.lstrip("/")))
                )
                for path in resolved_dirs:
                    for group_name in update.get("groups", {}):
                        prior = seen_by_dir.setdefault(path, {})
                        self.assertNotIn(
                            group_name,
                            prior,
                            f"Group {group_name!r} collides on {path.relative_to(ROOT.resolve())} between {prior.get(group_name)} and {update['package-ecosystem']}",
                        )
                        prior[group_name] = update["package-ecosystem"]

    def test_camoufox_playwright_and_camoufox_pins_are_isolated_from_chromium(self):
        # Playwright 1.65 breaks the pinned Camoufox Firefox (#658, #676), while
        # Chromium follows upstream. Dependabot's pip file fetcher scans both
        # `directory` and its immediate subdirectories, so the Camoufox
        # requirements file must live at least two levels below /services/base
        # with its own pip entry and ignore rules.
        base_dir = ROOT / "services/base"
        dockerfile = (base_dir / "Dockerfile").read_text(encoding="utf-8")
        stages = dict(
            re.findall(r"^FROM \S+ AS (\S+)$\n(.*?)(?=^FROM |\Z)", dockerfile, re.MULTILINE | re.DOTALL)
        )
        copy_match = re.search(r"^COPY\s+(\S*requirements\S*\.txt)\s+/opt/sidecar-base/", stages["camoufox"], re.MULTILINE)
        self.assertIsNotNone(copy_match, "camoufox stage must COPY a requirements file")
        camoufox_req = (base_dir / copy_match.group(1)).resolve()
        self.assertTrue(camoufox_req.is_file(), f"{camoufox_req} does not exist")

        rel_dir = camoufox_req.parent.relative_to(base_dir.resolve())
        self.assertGreaterEqual(
            len(rel_dir.parts),
            2,
            f"Camoufox requirements must sit >=2 levels below services/base so /services/base does not scan it: {rel_dir}",
        )

        # Ensure /services/base and its immediate subdirectories do not contain
        # a camoufox pin that the /services/base pip entry would pick up.
        scanned_by_base = list(base_dir.glob("*.txt")) + list(base_dir.glob("*/*.txt"))
        for path in scanned_by_base:
            text = path.read_text(encoding="utf-8")
            self.assertNotRegex(text, r"(?m)^camoufox\b", f"{path} would be scanned by /services/base")

        camoufox_text = camoufox_req.read_text(encoding="utf-8")
        self.assertRegex(camoufox_text, r"(?m)^camoufox\[geoip\]==0\.4\.")
        self.assertRegex(camoufox_text, r"(?m)^playwright==1\.64\.")

        config = yaml.safe_load((ROOT / ".github/dependabot.yml").read_text(encoding="utf-8"))
        pip_updates = {
            u["directory"]: u for u in config["updates"]
            if u["package-ecosystem"] == "pip" and "directory" in u
        }

        base_update = pip_updates["/services/base"]
        base_ignored = {i["dependency-name"] for i in base_update.get("ignore", [])}
        self.assertNotIn("playwright", base_ignored, "Ignoring playwright in /services/base would freeze Chromium")

        camoufox_dir = "/" + camoufox_req.parent.relative_to(ROOT.resolve()).as_posix()
        self.assertIn(camoufox_dir, pip_updates)
        camoufox_ignores = {
            i["dependency-name"]: i.get("versions")
            for i in pip_updates[camoufox_dir].get("ignore", [])
        }
        self.assertEqual(camoufox_ignores.get("camoufox"), [">=0.5"])
        self.assertEqual(camoufox_ignores.get("playwright"), [">=1.65"])


if __name__ == "__main__":
    unittest.main()
