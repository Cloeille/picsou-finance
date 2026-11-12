"""Splits the backend test classes across CI shards and checks every one ran exactly once.

  backend_shards.py --shards N --shard I --out DIR [--timings FILE]
      Writes shard I's surefire file to DIR. Shards 1..N-1 get an includesFile listing
      their classes; shard N gets an excludesFile listing every other shard's classes,
      so a class this script does not discover still runs there. Classes are balanced
      by the run times in the --timings JSON file (a class without one counts as the
      median), or spread by a stable hash when the file is missing.
      Writes `includes=<file>` or `excludes=<file>` to $GITHUB_OUTPUT.
  backend_shards.py --write-timings DIR FILE
      Writes FILE: {"com/picsou/x/FooTest": seconds} from the surefire XML reports
      under DIR, nested suites added to their top-level class.
  backend_shards.py --check DIR
      DIR holds one folder of surefire reports per shard. Fails when a test class
      reported in two folders, or a discovered one in none; prints the test totals.

A test class is a top-level, non-abstract class under backend/src/test/java whose file
matches surefire's default includes (Test*.java, *Test.java, *Tests.java, *TestCase.java)
and declares a JUnit test: a helper such as TestEntities matches but never reports.
"""

import argparse
from collections import defaultdict
import json
import os
from pathlib import Path
import re
import statistics
import sys
import xml.etree.ElementTree as ElementTree
import zlib

ROOT = Path(__file__).resolve().parents[2]
TESTS = ROOT / "backend/src/test/java"
NAME = re.compile(r"Test.*|.*Test|.*Tests|.*TestCase")
JUNIT_TEST = re.compile(r"@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b")
# Surefire excludes nested classes by default; an excludesFile replaces that default.
NESTED = "**/*$*"


def discover(tests=TESTS):
    """The test classes, as paths like com/picsou/x/FooTest (no extension)."""
    classes = []
    for source in sorted(tests.rglob("*.java")):
        name = source.stem
        if not NAME.fullmatch(name):
            continue
        text = source.read_text(encoding="utf-8")
        if re.search(rf"\b(abstract\s+class|interface|@interface|enum)\s+{name}\b", text):
            continue
        if not JUNIT_TEST.search(text):
            continue
        classes.append(source.relative_to(tests).with_suffix("").as_posix())
    return classes


def _top_level(report_name):
    """com.picsou.x.FooTest$Inner -> com/picsou/x/FooTest."""
    return report_name.split("$", 1)[0].replace(".", "/")


def report_timings(directory):
    """Seconds per top-level class, summed over its nested suites, from TEST-*.xml reports."""
    seconds = defaultdict(float)
    for report in Path(directory).rglob("TEST-*.xml"):
        try:
            suite = ElementTree.parse(report).getroot()
            seconds[_top_level(suite.attrib["name"])] += float(suite.attrib["time"].replace(",", ""))
        except (ElementTree.ParseError, KeyError, ValueError):
            continue
    return dict(sorted(seconds.items()))


def write_timings(directory, path):
    Path(path).write_text(json.dumps(report_timings(directory), indent=0) + "\n", encoding="utf-8")


def read_timings(path):
    """The --timings file, or {} when there is none (a cache miss)."""
    if not path or not Path(path).is_file():
        return {}
    return {name: float(seconds) for name, seconds in json.loads(Path(path).read_text(encoding="utf-8")).items()}


def assign(classes, shards, seconds=None):
    """The classes of each shard: longest first onto the least loaded one, else by hash."""
    bins = [[] for _ in range(shards)]
    known = {name: seconds[name] for name in classes if seconds and name in seconds}
    if not known:
        for name in classes:
            bins[zlib.crc32(name.encode()) % shards].append(name)
        return [sorted(names) for names in bins]
    default = statistics.median(known.values())
    loads = [0.0] * shards
    for name in sorted(classes, key=lambda name: (-known.get(name, default), name)):
        target = min(range(shards), key=lambda index: (loads[index], index))
        bins[target].append(name)
        loads[target] += known.get(name, default)
    return [sorted(names) for names in bins]


def shard_file(bins, shard):
    """('includes' | 'excludes', lines) for 1-based shard of len(bins)."""
    if shard < len(bins):
        return "includes", [f"{name}.class" for name in bins[shard - 1]]
    others = sorted(name for names in bins[:-1] for name in names)
    return "excludes", [f"{name}.class" for name in others] + [NESTED]


def split(shards, shard, out, timings_file=None, tests=TESTS):
    classes = discover(tests)
    seconds = read_timings(timings_file)
    bins = assign(classes, shards, seconds)
    # An empty includesFile makes surefire fall back to its defaults: every class.
    if any(not names for names in bins[:-1]):
        sys.exit(f"{len(classes)} test classes do not fill {shards} shards")
    kind, lines = shard_file(bins, shard)
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    path = out / f"backend-shard-{shard}.{kind}"
    path.write_text("".join(f"{line}\n" for line in lines), encoding="utf-8")
    source = f"timings for {sum(name in seconds for name in classes)}/{len(classes)} classes" if seconds else "hash"
    for index, names in enumerate(bins, 1):
        estimate = sum(seconds.get(name, 0.0) for name in names)
        print(f"shard {index}: {len(names)} classes, {estimate:.0f} s timed ({source})")
    print(f"{kind}={path}")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"{kind}={path}\n")
    return path


def _reported(folder):
    names = {report.name[len("TEST-"):-len(".xml")] for report in folder.glob("TEST-*.xml")}
    names |= {report.stem for report in folder.glob("*.txt")}
    return {_top_level(name) for name in names}


def check(directory, tests=TESTS):
    """Error messages for classes run twice or never; prints the summed counts."""
    folders = sorted(path for path in Path(directory).iterdir() if path.is_dir())
    where = defaultdict(list)
    for folder in folders:
        for name in _reported(folder):
            where[name].append(folder.name)
    errors = [f"{name} ran in {', '.join(found)}" for name, found in sorted(where.items()) if len(found) > 1]
    errors += [f"{name} has no report" for name in discover(tests) if name not in where]
    totals = defaultdict(int)
    for folder in folders:
        for report in folder.glob("TEST-*.xml"):
            suite = ElementTree.parse(report).getroot()
            for key in ("tests", "failures", "errors", "skipped"):
                totals[key] += int(suite.attrib.get(key, 0))
    print(f"{len(where)} test classes over {len(folders)} shards: " + ", ".join(
        f"{key} {totals[key]}" for key in ("tests", "failures", "errors", "skipped")))
    return errors


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", metavar="DIR")
    parser.add_argument("--shards", type=int)
    parser.add_argument("--shard", type=int)
    parser.add_argument("--out")
    parser.add_argument("--timings", metavar="FILE")
    parser.add_argument("--write-timings", nargs=2, metavar=("DIR", "FILE"))
    args = parser.parse_args(argv)
    if args.write_timings:
        write_timings(*args.write_timings)
        return 0
    if args.check:
        errors = check(args.check)
        for error in errors:
            print(f"::error::{error}", file=sys.stderr)
        return 1 if errors else 0
    if not (args.shards and args.shard and args.out) or not 1 <= args.shard <= args.shards:
        parser.error("--shards N --shard I (1 <= I <= N) --out DIR, --check DIR or --write-timings DIR FILE")
    split(args.shards, args.shard, args.out, args.timings)
    return 0


if __name__ == "__main__":
    sys.exit(main())
