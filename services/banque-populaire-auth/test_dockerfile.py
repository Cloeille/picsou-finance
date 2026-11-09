"""The images must ship every module main.py imports, transitively.

A missing module once shipped an image that crashed on `import main`. The local
closure is main.py alone; the login, replay and import code comes from the
shared `bpce` package, which the base image copies module by module.
"""

import ast
import pathlib
import re
import unittest

SERVICE_DIR = pathlib.Path(__file__).resolve().parent
BASE_DIR = SERVICE_DIR.parent / "base"
PACKAGE = "bpce"
RUNTIME_ROOT = "main"
# Test-only modules are never part of an image.
NOT_RUNTIME = {"testing"}


def local_modules() -> set[str]:
    return {p.stem for p in SERVICE_DIR.glob("*.py")}


def _imports(path: pathlib.Path, package: str | None) -> set[str]:
    """Top-level local names, or with `package` the package modules, that `path` imports."""
    found: set[str] = set()
    for node in ast.walk(ast.parse(path.read_text())):
        if isinstance(node, ast.Import):
            for alias in node.names:
                parts = alias.name.split(".")
                found.add(parts[1] if package and parts[0] == package and len(parts) > 1 else parts[0])
        elif isinstance(node, ast.ImportFrom):
            if node.level and package:  # `from . import x` / `from .x import y`
                if node.module:
                    found.add(node.module.split(".")[0])
                else:
                    found.update(alias.name for alias in node.names)
            elif node.module and node.module.split(".")[0] == package:
                parts = node.module.split(".")
                found.update([parts[1]] if len(parts) > 1 else [alias.name for alias in node.names])
            elif node.module and not package:
                found.add(node.module.split(".")[0])
    return found


def imported_local_modules(module: str, seen: set[str] | None = None) -> set[str]:
    seen = set() if seen is None else seen
    if module in seen:
        return seen
    seen.add(module)
    for name in _imports(SERVICE_DIR / f"{module}.py", None) & local_modules():
        imported_local_modules(name, seen)
    return seen


def imported_package_modules() -> set[str]:
    """The `bpce` modules main.py needs, transitively within the package."""
    package_dir = BASE_DIR / PACKAGE
    known = {p.stem for p in package_dir.glob("*.py")}
    todo = _imports(SERVICE_DIR / f"{RUNTIME_ROOT}.py", PACKAGE) & known
    seen: set[str] = set()
    while todo:
        module = todo.pop()
        seen.add(module)
        todo |= (_imports(package_dir / f"{module}.py", PACKAGE) & known) - seen
    return seen | {"__init__"}


def copied_files(dockerfile: pathlib.Path) -> set[str]:
    text = dockerfile.read_text().replace("\\\n", " ")
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
            needed - copied_files(SERVICE_DIR / "Dockerfile"), set(), "Dockerfile COPY misses runtime modules"
        )

    def test_the_sidecar_ships_main_alone(self):
        self.assertEqual(imported_local_modules(RUNTIME_ROOT), {"main"})
        self.assertEqual(copied_files(SERVICE_DIR / "Dockerfile"), {"main.py"})

    def test_the_base_image_ships_every_bpce_module_main_needs(self):
        needed = {f"{PACKAGE}/{m}.py" for m in imported_package_modules()}
        self.assertTrue({f"{PACKAGE}/login.py", f"{PACKAGE}/replay.py", f"{PACKAGE}/fetcher.py"} <= needed)
        self.assertEqual(needed - copied_files(BASE_DIR / "Dockerfile"), set())

    def test_no_test_module_is_copied_into_the_image(self):
        for name in copied_files(SERVICE_DIR / "Dockerfile") | copied_files(BASE_DIR / "Dockerfile"):
            stem = pathlib.PurePosixPath(name).stem
            self.assertFalse(stem.startswith("test_") or stem in NOT_RUNTIME, name)
        self.assertNotIn("testing", imported_package_modules())

    def test_the_image_builds_on_the_chromium_base_and_runs_unprivileged(self):
        # Chromium and the Playwright pin live in services/base, shared with every
        # other browser sidecar; see .github/tests/test_sidecar_workflows.py.
        text = (SERVICE_DIR / "Dockerfile").read_text()
        self.assertRegex(text, r"(?m)^ARG SIDECAR_BASE=picsou-sidecar-chromium$")
        self.assertRegex(text, r"(?m)^FROM \$\{SIDECAR_BASE\}$")
        self.assertRegex(text, r"(?m)^USER banquepop$")
        base = (BASE_DIR / "Dockerfile").read_text()
        self.assertIn("playwright install chromium", base)
        self.assertNotRegex(base, r"playwright install(?! chromium)")

    def test_the_helper_catches_a_missing_module(self):
        # Guards the guard: the closure walk really follows the package's own imports.
        self.assertIn("keypad_table", imported_package_modules())
        self.assertIn("site", imported_package_modules())


if __name__ == "__main__":
    unittest.main()
