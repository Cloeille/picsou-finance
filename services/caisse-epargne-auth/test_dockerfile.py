"""The Dockerfile must COPY every local module main.py imports, transitively.

A missing module here once shipped an image that crashed on `import main`.
"""

import ast
import pathlib
import re
import unittest

SERVICE_DIR = pathlib.Path(__file__).resolve().parent
RUNTIME_ROOT = "main"
# Test-only modules are never part of the image.
NOT_RUNTIME = {"fake_browser"}


def local_modules() -> set[str]:
    return {p.stem for p in SERVICE_DIR.glob("*.py")} - NOT_RUNTIME


def imported_local_modules(module: str, seen: set[str] | None = None) -> set[str]:
    seen = set() if seen is None else seen
    if module in seen:
        return seen
    seen.add(module)
    tree = ast.parse((SERVICE_DIR / f"{module}.py").read_text())
    known = local_modules()
    for node in ast.walk(tree):
        names: list[str] = []
        if isinstance(node, ast.Import):
            names = [alias.name.split(".")[0] for alias in node.names]
        elif isinstance(node, ast.ImportFrom) and node.module and node.level == 0:
            names = [node.module.split(".")[0]]
        for name in names:
            if name in known:
                imported_local_modules(name, seen)
    return seen


def copied_files() -> set[str]:
    text = (SERVICE_DIR / "Dockerfile").read_text().replace("\\\n", " ")
    files: set[str] = set()
    for line in text.splitlines():
        match = re.match(r"\s*COPY\s+(.+)", line)
        if match:
            parts = match.group(1).split()
            files.update(p for p in parts[:-1] if not p.startswith("--"))
    return files


class DockerfileCopyTest(unittest.TestCase):
    def test_every_transitively_imported_module_is_copied(self):
        needed = {f"{m}.py" for m in imported_local_modules(RUNTIME_ROOT)}
        self.assertEqual(
            needed - copied_files(), set(), "Dockerfile COPY misses runtime modules"
        )

    def test_the_login_modules_are_part_of_the_runtime_closure(self):
        needed = {f"{m}.py" for m in imported_local_modules(RUNTIME_ROOT)}
        self.assertEqual(
            needed,
            {
                "main.py", "replay.py", "fetcher.py", "accounts_parser.py",
                "login.py", "keypad_table.py",
            },
        )

    def test_no_test_module_is_copied_into_the_image(self):
        for name in copied_files():
            self.assertFalse(name.startswith("test_") or name == "fake_browser.py", name)

    def test_the_image_installs_chromium_only_and_runs_unprivileged(self):
        text = (SERVICE_DIR / "Dockerfile").read_text()
        self.assertIn("playwright install chromium", text)
        self.assertNotRegex(text, r"playwright install(?! chromium)")
        self.assertIn("PLAYWRIGHT_BROWSERS_PATH", text)
        self.assertRegex(text, r"(?m)^USER caisse$")

    def test_playwright_is_pinned_to_the_amundi_version(self):
        requirements = (SERVICE_DIR / "requirements.txt").read_text()
        amundi = (SERVICE_DIR.parent / "amundi-auth" / "requirements.txt").read_text()
        pin = re.search(r"(?m)^playwright==[\d.]+$", amundi)
        self.assertIsNotNone(pin)
        self.assertIn(pin.group(0), requirements)

    def test_the_helper_catches_a_missing_module(self):
        # Guards the guard: the closure walk really follows imports.
        self.assertIn("keypad_table", imported_local_modules("login"))


if __name__ == "__main__":
    unittest.main()
