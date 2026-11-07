"""The Vitest coverage check fails unless every test file ran exactly once across the shards."""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location("vitest_coverage", ROOT / ".github/scripts/vitest_coverage.py")
vitest_coverage = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(vitest_coverage)


class VitestCoverageTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name).resolve()
        self.src = self.root / "frontend/src"
        for name in ("a.test.ts", "ui/b.spec.tsx", "ui/c.test.tsx", "d.spec.ts", "helper.ts", "e.test.js"):
            (self.src / name).parent.mkdir(parents=True, exist_ok=True)
            (self.src / name).write_text("")
        self.reports = self.root / "reports"

    def report(self, folder, *names):
        path = self.reports / folder / "r.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"testResults": [{"name": f"{self.src.as_posix()}/{n}"} for n in names]}))

    def run_check(self):
        return vitest_coverage.main([str(self.src), str(self.reports)])

    def test_passes_when_the_shards_partition_the_test_files(self):
        self.report("vitest-test-1", "a.test.ts", "ui/b.spec.tsx")
        self.report("vitest-test-2", "ui/c.test.tsx", "d.spec.ts")
        self.assertEqual(self.run_check(), 0)

    def test_only_ts_and_tsx_test_and_spec_files_are_expected(self):
        self.assertEqual(vitest_coverage.expected_files(self.src),
                         {"a.test.ts", "ui/b.spec.tsx", "ui/c.test.tsx", "d.spec.ts"})

    def test_fails_when_a_file_never_ran(self):
        self.report("vitest-test-1", "a.test.ts", "ui/b.spec.tsx", "ui/c.test.tsx")
        self.assertEqual(self.run_check(), 1)

    def test_fails_when_a_file_ran_twice(self):
        self.report("vitest-test-1", "a.test.ts", "ui/b.spec.tsx", "ui/c.test.tsx")
        self.report("vitest-test-2", "ui/c.test.tsx", "d.spec.ts")
        self.assertEqual(self.run_check(), 1)

    def test_fails_when_a_report_names_an_unknown_file(self):
        self.report("vitest-test-1", "a.test.ts", "ui/b.spec.tsx", "ui/c.test.tsx", "d.spec.ts", "gone.test.ts")
        self.assertEqual(self.run_check(), 1)

    def test_reads_reports_from_a_checkout_under_a_src_directory(self):
        path = self.reports / "vitest-test-1" / "r.json"
        path.parent.mkdir(parents=True)
        path.write_text(json.dumps({"testResults": [
            {"name": f"/home/u/src/picsou/frontend/src/{n}"}
            for n in ("a.test.ts", "ui/b.spec.tsx", "ui/c.test.tsx", "d.spec.ts")]}))
        self.assertEqual(self.run_check(), 0)

    def test_fails_without_any_report(self):
        self.reports.mkdir()
        with self.assertRaises(ValueError):
            self.run_check()


if __name__ == "__main__":
    unittest.main()
