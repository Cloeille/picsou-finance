"""The backend shards cover every test class exactly once, and the check catches when they do not."""

import importlib.util
import io
import json
import os
from contextlib import redirect_stdout
from pathlib import Path
import re
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location("backend_shards", ROOT / ".github/scripts/backend_shards.py")
shards = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(shards)


def _write(root, relative, text):
    path = Path(root) / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def _report(folder, name, seconds=1.0, tests=1):
    _write(folder, f"TEST-{name}.xml", f'<testsuite name="{name}" time="{seconds}" tests="{tests}" '
                                       'failures="0" errors="0" skipped="0"/>')
    _write(folder, f"{name}.txt", f"Tests run: {tests}, Failures: 0, Errors: 0, Skipped: 0\n")


def _surefire_selects(pattern, path):
    """Surefire's pattern match on a class path: **/ spans folders, * stays within one."""
    regex = "".join(
        "(.*/)?" if part == "**/" else "[^/]*" if part == "*" else re.escape(part)
        for part in re.split(r"(\*\*/|\*)", pattern)
    )
    return re.fullmatch(regex, path) is not None


def _runs(classes, shard, bins):
    """The discovered classes a shard runs, as surefire would select them."""
    kind, lines = shards.shard_file(bins, shard)
    paths = [f"{name}.class" for name in classes]
    if kind == "includes":
        return {path for path in paths if path in lines}
    return {path for path in paths if not any(_surefire_selects(line, path) for line in lines)}


class DiscoveryTest(unittest.TestCase):
    def test_only_concrete_top_level_classes_named_like_surefire_includes(self):
        with tempfile.TemporaryDirectory() as root:
            for name in ("FooTest", "FooTests", "TestBar", "BazTestCase", "Helper", "Fixtures"):
                _write(root, f"com/picsou/x/{name}.java", f"class {name} {{ @Test void a() {{}} }}")
            _write(root, "com/picsou/x/BaseTest.java", "public abstract class BaseTest { @Test void a() {} }")
            _write(root, "com/picsou/x/MarkerTest.java", "interface MarkerTest {}")
            _write(root, "com/picsou/x/RequiresTest.java", "public @interface RequiresTest {}")
            _write(root, "com/picsou/x/TestEntities.java", "public final class TestEntities {}")
            _write(root, "com/picsou/x/OuterTest.java",
                   "class OuterTest { @Nested class InnerTest { @ParameterizedTest void a() {} } }")

            self.assertEqual(shards.discover(Path(root)), [
                "com/picsou/x/BazTestCase", "com/picsou/x/FooTest", "com/picsou/x/FooTests",
                "com/picsou/x/OuterTest", "com/picsou/x/TestBar",
            ])


class AssignTest(unittest.TestCase):
    CLASSES = [f"com/picsou/C{index}Test" for index in range(40)]

    def test_timings_balance_the_shards_longest_first(self):
        seconds = {name: float(index % 7 + 1) for index, name in enumerate(self.CLASSES)}
        bins = shards.assign(self.CLASSES, 3, seconds)
        loads = [sum(seconds[name] for name in names) for names in bins]
        self.assertLessEqual(max(loads) - min(loads), max(seconds.values()))

    def test_a_class_without_a_timing_counts_as_the_median(self):
        seconds = {"a/ATest": 10.0, "a/BTest": 1.0, "a/CTest": 2.0}
        bins = shards.assign(["a/ATest", "a/BTest", "a/CTest", "a/NewTest"], 2, seconds)
        self.assertEqual(bins, [["a/ATest"], ["a/BTest", "a/CTest", "a/NewTest"]])

    def test_without_timings_the_split_is_a_stable_hash(self):
        self.assertEqual(shards.assign(self.CLASSES, 3), shards.assign(list(reversed(self.CLASSES)), 3, {}))

    def test_the_timings_file_sums_nested_suites_into_their_top_level_class(self):
        with tempfile.TemporaryDirectory() as folder:
            _report(Path(folder) / "surefire-1", "com.picsou.x.FooTest", 1.5)
            _report(Path(folder) / "surefire-2", "com.picsou.x.FooTest$Nested", 2.0)
            _report(Path(folder) / "surefire-2", "com.picsou.x.BarTest", 0.25)
            path = Path(folder) / "timings.json"
            self.assertEqual(shards.main(["--write-timings", folder, str(path)]), 0)
            self.assertEqual(json.loads(path.read_text()), {"com/picsou/x/BarTest": 0.25, "com/picsou/x/FooTest": 3.5})
            self.assertEqual(shards.read_timings(path), {"com/picsou/x/BarTest": 0.25, "com/picsou/x/FooTest": 3.5})

    def test_a_missing_timings_file_falls_back_to_the_hash(self):
        with tempfile.TemporaryDirectory() as folder:
            self.assertEqual(shards.read_timings(Path(folder) / "backend-timings.json"), {})
        self.assertEqual(shards.read_timings(None), {})


class ExactlyOnceTest(unittest.TestCase):
    def assert_exactly_once(self, classes, bins):
        runs = [_runs(classes, shard, bins) for shard in range(1, len(bins) + 1)]
        everything = [path for shard in runs for path in shard]
        self.assertEqual(sorted(everything), sorted(f"{name}.class" for name in classes))

    def test_every_real_backend_class_runs_in_exactly_one_hashed_shard(self):
        classes = shards.discover()
        self.assertGreater(len(classes), 100)
        for count in (2, 3, 4):
            with self.subTest(shards=count):
                self.assert_exactly_once(classes, shards.assign(classes, count))

    def test_every_real_backend_class_runs_in_exactly_one_timed_shard(self):
        classes = shards.discover()
        seconds = {name: float(len(name) % 11) for name in classes[::2]}
        self.assert_exactly_once(classes, shards.assign(classes, 3, seconds))

    def test_the_last_shard_runs_new_classes_and_keeps_excluding_nested_ones(self):
        bins = [["a/ATest"], ["a/BTest"], ["a/CTest"]]
        kind, lines = shards.shard_file(bins, 3)
        self.assertEqual((kind, lines), ("excludes", ["a/ATest.class", "a/BTest.class", "**/*$*"]))
        self.assertEqual(_runs(["a/ATest", "a/CTest", "a/NewTest", "a/ATest$Inner"], 3, bins),
                         {"a/CTest.class", "a/NewTest.class"})

    def test_split_writes_the_shard_file_and_its_github_output(self):
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as out:
            for index in range(20):
                _write(root, f"a/C{index}Test.java", f"class C{index}Test {{ @Test void a() {{}} }}")
            output = Path(out) / "github-output"
            written = {}
            for shard in (1, 2):
                with redirect_stdout(io.StringIO()), mock.patch.dict(os.environ, {"GITHUB_OUTPUT": str(output)}):
                    written[shard] = shards.split(2, shard, out, tests=Path(root))
            self.assertEqual(written[1].name, "backend-shard-1.includes")
            self.assertEqual(written[2].name, "backend-shard-2.excludes")
            included = written[1].read_text().split()
            self.assertTrue(included)
            self.assertEqual(written[2].read_text().split(), included + ["**/*$*"])
            self.assertEqual(output.read_text().splitlines(), [f"includes={written[1]}", f"excludes={written[2]}"])

    def test_split_balances_on_the_timings_file(self):
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as out:
            for name in ("ATest", "BTest", "CTest"):
                _write(root, f"a/{name}.java", f"class {name} {{ @Test void a() {{}} }}")
            timings = Path(out) / "timings.json"
            timings.write_text(json.dumps({"a/ATest": 9.0, "a/BTest": 5.0, "a/CTest": 4.0}))
            with redirect_stdout(io.StringIO()):
                written = shards.split(2, 1, out, timings, tests=Path(root))
            self.assertEqual(written.read_text().split(), ["a/ATest.class"])

    def test_split_refuses_an_empty_includes_file(self):
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as out:
            _write(root, "a/ATest.java", "class ATest { @Test void a() {} }")
            with self.assertRaises(SystemExit), redirect_stdout(io.StringIO()):
                shards.split(3, 1, out, tests=Path(root))


class CheckTest(unittest.TestCase):
    def check(self, reports, sources=("a.ATest", "a.BTest")):
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as directory:
            for name in sources:
                _write(root, name.replace(".", "/") + ".java", "class X { @Test void a() {} }")
            for folder, names in reports.items():
                (Path(directory) / folder).mkdir()
                for name in names:
                    _report(Path(directory) / folder, name, tests=2)
            output = io.StringIO()
            with redirect_stdout(output):
                errors = shards.check(directory, tests=Path(root))
            return errors, output.getvalue()

    def test_each_class_in_one_shard_passes_and_prints_the_totals(self):
        errors, output = self.check({"surefire-1": ["a.ATest", "a.ATest$Nested"], "surefire-2": ["a.BTest"]})
        self.assertEqual(errors, [])
        self.assertIn("2 test classes over 2 shards: tests 6", output)

    def test_a_class_in_two_shards_fails_even_through_a_nested_report(self):
        errors, _ = self.check({"surefire-1": ["a.ATest"], "surefire-2": ["a.BTest", "a.ATest$Nested"]})
        self.assertEqual(errors, ["a/ATest ran in surefire-1, surefire-2"])

    def test_a_class_without_a_report_fails(self):
        errors, _ = self.check({"surefire-1": ["a.ATest"], "surefire-2": []})
        self.assertEqual(errors, ["a/BTest has no report"])


if __name__ == "__main__":
    unittest.main()
