#!/usr/bin/env python3
"""Fail unless the Vitest JSON reports ran every frontend test file exactly once.

Usage: vitest_coverage.py <frontend/src dir> <directory holding the JSON reports>
"""

import json
import sys
from collections import Counter
from pathlib import Path

SUFFIXES = (".test.ts", ".test.tsx", ".spec.ts", ".spec.tsx")


def expected_files(src):
    src = Path(src).resolve()
    return {p.relative_to(src).as_posix() for p in src.rglob("*") if p.is_file() and p.name.endswith(SUFFIXES)}


def executed_files(report_dir, src):
    """Every test file named by a report, as a path relative to src, one entry per occurrence."""
    src = Path(src).resolve()
    anchor = "/" + "/".join(src.parts[-2:]) + "/"
    src = src.as_posix()
    executed = Counter()
    reports = sorted(Path(report_dir).rglob("*.json"))
    if not reports:
        raise ValueError(f"no JSON report under {report_dir}")
    for report in reports:
        for result in json.loads(report.read_text(encoding="utf-8"))["testResults"]:
            name = result["name"].replace("\\", "/")
            executed[name.removeprefix(src + "/") if name.startswith(src + "/") else name.rsplit(anchor, 1)[-1]] += 1
    return executed


def problems(expected, executed):
    found = []
    missing = sorted(expected - executed.keys())
    extra = sorted(executed.keys() - expected)
    twice = sorted(name for name, count in executed.items() if count > 1)
    if missing:
        found.append("never ran: " + ", ".join(missing))
    if extra:
        found.append("ran but match no test file: " + ", ".join(extra))
    if twice:
        found.append("ran more than once: " + ", ".join(twice))
    return found


def main(argv):
    src, report_dir = argv
    expected = expected_files(src)
    found = problems(expected, executed_files(report_dir, src))
    if found:
        print("\n".join(found), file=sys.stderr)
        return 1
    print(f"{len(expected)} test files ran exactly once")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
