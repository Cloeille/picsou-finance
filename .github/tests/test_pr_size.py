"""The pull-request size nudge counts reviewable lines and speaks only above the budget."""

from importlib.machinery import SourceFileLoader
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
pr_size = SourceFileLoader("pr_size", str(ROOT / ".github/scripts/pr_size.py")).load_module()


class PrSizeTest(unittest.TestCase):
    def test_lockfiles_changelog_snapshots_and_fixtures_are_not_counted(self):
        files = [
            ("backend/src/main/java/com/picsou/A.java", 100, 20),
            ("frontend/bun.lock", 5000, 4000),
            ("services/tr-auth/requirements.lock", 300, 0),
            ("CHANGELOG.md", 40, 0),
            ("frontend/src/__snapshots__/Chart.test.tsx.snap", 900, 0),
            ("backend/src/test/resources/fixtures/sync.json", 700, 0),
            ("services/bourso-auth/tests/fixtures/page.html", 500, 0),
        ]
        self.assertEqual(pr_size.counted(files), 120)

    def test_documentation_and_tests_are_counted(self):
        files = [("docs/features/x.md", 30, 0), ("backend/src/test/java/com/picsou/AT.java", 50, 10)]
        self.assertEqual(pr_size.counted(files), 90)

    def test_comment_flags_only_what_is_over_the_budget(self):
        over = pr_size.render(pr_size.BUDGET + 1, "o/r")
        self.assertIn(f"**{pr_size.BUDGET + 1}** lines", over)
        self.assertIn("nudge, not a check that blocks", over)
        self.assertIn("https://github.com/o/r/blob/main/CONTRIBUTING.md#before-you-open-a-pull-request", over)
        self.assertTrue(over.startswith(pr_size.MARKER))
        under = pr_size.render(pr_size.BUDGET, "o/r")
        self.assertIn("within the soft budget", under)
        self.assertTrue(under.startswith(pr_size.MARKER))


if __name__ == "__main__":
    unittest.main()
