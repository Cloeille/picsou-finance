"""The CodeQL language selection of codeql.yml."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / ".github/scripts/codeql_languages.py"
sys.path.insert(0, str(SCRIPT.parent))

import codeql_languages  # noqa: E402

ALL = codeql_languages.LANGUAGES


class CodeqlLanguagesTest(unittest.TestCase):
    def test_backend_only(self):
        self.assertEqual(codeql_languages.languages(["backend/src/A.java"]), ["java-kotlin"])

    def test_frontend_only(self):
        self.assertEqual(
            codeql_languages.languages(["frontend/src/a.ts", ".design-sync/x.json"]),
            ["javascript-typescript"],
        )

    def test_github_only_is_python_and_actions(self):
        self.assertEqual(codeql_languages.languages([".github/workflows/ci.yml"]), ["python", "actions"])

    def test_docs_only_selects_nothing(self):
        self.assertEqual(codeql_languages.languages(["docs/a/b.png", "README.md", "CHANGELOG.md"]), [])

    def test_unmapped_file_selects_everything(self):
        self.assertEqual(codeql_languages.languages(["Makefile"]), ALL)
        self.assertEqual(codeql_languages.languages(["backend/A.java", "Makefile"]), ALL)

    def test_mixed(self):
        self.assertEqual(
            codeql_languages.languages(["backend/A.java", "frontend/a.ts", "docs/x.md"]),
            ["java-kotlin", "javascript-typescript"],
        )

    def test_services_docker_charts_are_python(self):
        self.assertEqual(
            codeql_languages.languages(["services/x-auth/a.py", "docker/Dockerfile", "charts/picsou/Chart.yaml"]),
            ["python"],
        )

    def test_no_list_selects_everything(self):
        self.assertEqual(codeql_languages.languages(None), ALL)

    def run_script(self, **env):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "out"
            full = {k: v for k, v in os.environ.items() if k not in ("ALL", "FILES")}
            full.update(env, GITHUB_OUTPUT=str(out))
            subprocess.run([sys.executable, str(SCRIPT)], env=full, check=True)
            return json.loads(out.read_text(encoding="utf-8").split("matrix=", 1)[1])

    def test_all_flag_and_missing_list_select_everything(self):
        every = [{"language": language, "build-mode": "none"} for language in ALL]
        self.assertEqual(self.run_script(ALL="true", FILES='["docs/a.md"]'), every)
        self.assertEqual(self.run_script(), every)

    def test_pull_request_files_are_filtered(self):
        self.assertEqual(
            self.run_script(FILES='["backend/A.java"]'),
            [{"language": "java-kotlin", "build-mode": "none"}],
        )
        self.assertEqual(self.run_script(FILES='["docs/a.md"]'), [])


if __name__ == "__main__":
    unittest.main()
