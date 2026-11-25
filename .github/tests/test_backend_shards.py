"""The backend shards: every listed test lands in one shard, and the check fails unless each ran once."""

import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location("backend_shards", ROOT / ".github/scripts/backend_shards.py")
backend_shards = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(backend_shards)

NAMES = [f"module_{i % 4}::case_{i:02}" for i in range(11)]
LISTING = "".join(f"{name}: test\n" for name in NAMES) + "\n11 tests, 0 benchmarks\n"


def run_select(listing, shard):
    """The exit code, what the recipe passes to `--exact`, and what reaches the shard's output."""
    out, err = io.StringIO(), io.StringIO()
    with mock.patch("sys.stdin", io.StringIO(listing)), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = backend_shards.main(["select", shard])
    return code, out.getvalue(), err.getvalue()


class SelectTest(unittest.TestCase):
    def test_the_shards_partition_the_listing(self):
        for total in range(1, 6):
            with self.subTest(total=total):
                shards = [backend_shards.select(LISTING, f"{k}/{total}") for k in range(1, total + 1)]
                self.assertEqual(sorted(name for shard in shards for name in shard), sorted(NAMES))
                self.assertLessEqual(max(map(len, shards)) - min(map(len, shards)), 1)

    def test_prints_the_names_for_cargo_and_again_for_the_check(self):
        code, out, err = run_select(LISTING, "2/3")
        self.assertEqual((code, out), (0, " ".join(NAMES[1::3]) + "\n"))
        self.assertEqual(err, "selected 2/3: " + " ".join(NAMES[1::3]) + "\n")

    def test_a_shard_outside_its_total_and_an_empty_listing_are_errors(self):
        # A failed build lists nothing: `--exact` with no name would run every test.
        for listing, shard in ((LISTING, "0/3"), (LISTING, "4/3"), ("error: could not compile `picsou`\n", "1/2")):
            with self.subTest(shard=shard):
                code, out, _ = run_select(listing, shard)
                self.assertEqual((code, out), (1, ""))


class CheckTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.reports = Path(self._tmp.name)

    def report(self, folder, shard, names, total=len(NAMES), passed=None, failed=0):
        """A shard's output: the selection, then what `cargo test` prints while the application logs."""
        passed = len(names) if passed is None else passed
        lines = [f"selected {shard}: " + " ".join(names), "   Compiling picsou v0.1.0", f"running {len(names)} tests"]
        # libtest writes the name and the outcome apart: a log line can land between them.
        lines += [f"test {name} ... 2026-11-24T21:24:18Z  INFO import complete\nok" for name in names]
        lines += ["", f"test result: ok. {passed} passed; {failed} failed; 0 ignored; 0 measured; "
                      f"{total - passed - failed} filtered out; finished in 1.00s"]
        self.write(folder, "\n".join(lines) + "\n")

    def write(self, folder, text):
        path = self.reports / folder / "tests.txt"
        path.parent.mkdir(parents=True)
        path.write_text(text, encoding="utf-8")

    def shards(self, engine, total):
        for k in range(1, total + 1):
            self.report(f"backend-rs-{engine}-{k}", f"{k}/{total}", backend_shards.select(LISTING, f"{k}/{total}"))

    def run_check(self, *expected):
        with contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()):
            return backend_shards.main(["check", str(self.reports), *(expected or ("postgres=3", "sqlite=2"))])

    def test_passes_when_each_engine_ran_every_test_once(self):
        self.shards("postgres", 3)
        self.shards("sqlite", 2)
        self.assertEqual(self.run_check(), 0)

    def test_fails_when_an_engine_has_no_output(self):
        self.shards("postgres", 3)
        self.assertEqual(self.run_check(), 1)

    def test_fails_when_a_shard_is_missing(self):
        self.report("backend-rs-postgres-1", "1/2", NAMES[0::2])
        self.assertEqual(self.run_check("postgres=2"), 1)

    def test_fails_on_an_output_no_leg_is_expected_to_write(self):
        self.shards("postgres", 3)
        self.assertEqual(self.run_check("postgres=2"), 1)

    def test_fails_on_an_unexpected_folder_even_without_a_test_output(self):
        self.shards("postgres", 3)
        (self.reports / "backend-rs-extra").mkdir()
        self.assertEqual(self.run_check("postgres=3"), 1)

    def test_rejects_legs_that_would_check_nothing(self):
        self.shards("postgres", 3)
        for expected in (("postgres=0",), ("postgres=-1",), ("postgres=3", "postgres=1"), ("=3",), ("postgres",)):
            with self.subTest(expected=expected):
                self.assertEqual(self.run_check(*expected), 1)

    def test_fails_when_a_leg_ran_another_shard_than_its_own(self):
        self.report("backend-rs-sqlite-1", "1/2", NAMES[0::2])
        self.report("backend-rs-sqlite-2", "1/2", NAMES[0::2])
        self.assertEqual(self.run_check("sqlite=2"), 1)

    def test_fails_when_a_test_is_in_two_shards(self):
        self.report("backend-rs-postgres-1", "1/2", NAMES[0::2] + NAMES[1:2])
        self.report("backend-rs-postgres-2", "2/2", NAMES[1::2])
        self.assertEqual(self.run_check("postgres=2"), 1)

    def test_fails_when_the_shards_come_from_different_binaries(self):
        self.report("backend-rs-sqlite-1", "1/2", NAMES[0::2])
        self.report("backend-rs-sqlite-2", "2/2", NAMES[1::2], total=len(NAMES) + 1)
        self.assertEqual(self.run_check("sqlite=2"), 1)

    def test_fails_when_a_shard_stopped_before_its_result(self):
        self.report("backend-rs-sqlite-1", "1/2", NAMES[0::2])
        self.write("backend-rs-sqlite-2", "selected 2/2: " + " ".join(NAMES[1::2]) + "\nrunning 5 tests\n")
        self.assertEqual(self.run_check("sqlite=2"), 1)

    def test_fails_on_a_failed_test_and_on_a_selected_test_that_did_not_run(self):
        for case in ({"failed": 1, "passed": len(NAMES) - 1}, {"passed": len(NAMES) - 1}):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as reports:
                self.reports = Path(reports)
                self.report("backend-rs-sqlite-1", "1/1", NAMES, **case)
                self.assertEqual(self.run_check("sqlite=1"), 1)

    def test_fails_on_an_empty_selection(self):
        for selection in ("selected 1/1: ", "selected 1/1:   "):
            with self.subTest(selection=selection), tempfile.TemporaryDirectory() as reports:
                self.reports = Path(reports)
                self.write("backend-rs-sqlite-1", selection + "\ntest result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out\n")
                self.assertEqual(self.run_check("sqlite=1"), 1)

    def test_a_counts_like_line_from_the_application_is_not_a_result(self):
        self.write("backend-rs-sqlite-1", "selected 1/1: a b\nINFO 2 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out\n")
        self.assertEqual(self.run_check("sqlite=1"), 1)

    def test_an_ignored_test_counts_as_done(self):
        self.write("backend-rs-sqlite-1", "selected 1/1: a b\ntest result: ok. 1 passed; 0 failed; 1 ignored; 0 measured; 0 filtered out\n")
        self.assertEqual(self.run_check("sqlite=1"), 0)

    def test_select_reads_a_listing_with_windows_line_endings(self):
        self.assertEqual(backend_shards.select(LISTING.replace("\n", "\r\n"), "1/1"), NAMES)


if __name__ == "__main__":
    unittest.main()
