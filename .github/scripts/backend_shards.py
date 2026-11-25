#!/usr/bin/env python3
"""Split the backend's integration tests into shards, and check the shards ran every test once.

Usage: backend_shards.py select <k>/<n>
           reads `cargo test -- --list --format terse` on stdin and prints every nth test
           name, starting at the kth; repeats them on stderr for the check
       backend_shards.py check <dir> <engine>=<shards>...
           <dir>/backend-rs-<engine>-<k>/tests.txt is the output of shard k
"""

import re
import sys
from collections import Counter
from pathlib import Path

LISTED = re.compile(r"^(\S+): test\r?$", re.MULTILINE)
# A shard's output is read through these two lines only. libtest writes `test <name> ... ok` in
# two steps, and a log line of the application under test lands between them now and then. It
# writes the counts in one step, from the full stop on.
SELECTED = re.compile(r"^selected (\d+/\d+): (\S.*)$", re.MULTILINE)
COUNTS = re.compile(r"\. (\d+) passed; (\d+) failed; (\d+) ignored; (\d+) measured; (\d+) filtered out")


def select(listing, shard):
    """libtest lists the names sorted, so each shard gets a slice of every module."""
    k, n = (int(part) for part in shard.split("/"))
    if not 1 <= k <= n:
        raise ValueError(f"shard {shard}: expected k/n with 1 <= k <= n")
    return LISTED.findall(listing)[k - 1 :: n]


def legs(arguments):
    """`postgres=3 sqlite=2` as {engine: shards}."""
    expected = {}
    for argument in arguments:
        engine, _, shards = argument.partition("=")
        if not engine or engine in expected or not shards.isdigit() or int(shards) < 1:
            raise ValueError(f"{argument}: expected <engine>=<shards>, each engine once, at least one shard")
        expected[engine] = int(shards)
    return expected


def problems(reports, expected):
    """`expected` maps an engine to its number of shards. The counts of a shard include the
    tests it left out, so every shard states how many tests the binary holds. An ignored test
    counts as done, as it does in an unsharded run."""
    reports = Path(reports)
    wanted = {f"backend-rs-{engine}-{k}" for engine, shards in expected.items() for k in range(1, shards + 1)}
    present = {path.name for path in reports.iterdir()} if reports.is_dir() else set()
    found = [f"unexpected output: {name}" for name in sorted(present - wanted)]
    for engine, shards in sorted(expected.items()):
        ran, totals, before = Counter(), set(), len(found)
        for k in range(1, shards + 1):
            name = f"backend-rs-{engine}-{k}"
            if not (reports / name / "tests.txt").is_file():
                found.append(f"no output for {name}")
                continue
            log = (reports / name / "tests.txt").read_text(encoding="utf-8", errors="replace")
            selected, counts = SELECTED.findall(log), COUNTS.findall(log)
            if [shard for shard, _ in selected] != [f"{k}/{shards}"] or len(counts) != 1:
                found.append(f"{name}: expected one selection of {k}/{shards} and one result, "
                             f"found {[shard for shard, _ in selected]} and {len(counts)}")
                continue
            names = selected[0][1].split()
            passed, failed, ignored, measured, filtered = map(int, counts[0])
            if failed or len(names) != passed + ignored + measured:
                found.append(f"{name}: {len(names)} tests selected, {passed} passed, {failed} failed, {ignored} ignored")
            totals.add(passed + failed + ignored + measured + filtered)
            ran.update(names)
        twice = sorted(name for name, count in ran.items() if count > 1)
        if twice:
            found.append(f"{engine}: in two shards: " + ", ".join(twice))
        if len(totals) > 1:
            found.append(f"{engine}: the shards disagree on the number of tests: {sorted(totals)}")
        elif totals and len(ran) != max(totals):
            found.append(f"{engine}: {len(ran)} of {max(totals)} tests ran")
        elif len(found) == before:
            print(f"{engine}: {max(totals)} tests, each ran once in {shards} shards")
    return found


def main(argv):
    try:
        if len(argv) == 2 and argv[0] == "select":
            names = select(sys.stdin.read(), argv[1])
            if names:
                print(" ".join(names))
                print(f"selected {argv[1]}: " + " ".join(names), file=sys.stderr)
                return 0
            found = ["no test to select: the listing is empty"]
        elif len(argv) > 2 and argv[0] == "check":
            found = problems(argv[1], legs(argv[2:]))
        else:
            print(__doc__, file=sys.stderr)
            return 2
    except ValueError as error:
        found = [str(error)]
    for problem in found:
        print(f"::error::{problem}", file=sys.stderr)
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
