"""The sidecar inventory: every services/*-auth folder with a Dockerfile, and its shared base.

CI and publishing read the connector list from here, so adding a connector touches
no workflow file.

  sidecars.py                  JSON list of {"service", "base"}, one per sidecar
  sidecars.py --publish        docker.yml's build matrix: the app image, then every sidecar
  sidecars.py --select         ci.yml: writes `jobs` (the path filters that matched, $CHANGES,
                               without `sidecar` when no sidecar leg is left) and `matrix`
                               (one leg per affected sidecar and runner architecture) to
                               $GITHUB_OUTPUT. $ALL is 'true' when the path filter did not
                               run; $FILES lists the paths the `sidecar` filter matched.
"""

import json
import os
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
BASE = re.compile(r"(?m)^ARG SIDECAR_BASE=picsou-sidecar-(python|chromium|camoufox)$")


def inventory():
    sidecars = []
    for dockerfile in sorted((ROOT / "services").glob("*-auth/Dockerfile")):
        match = BASE.search(dockerfile.read_text(encoding="utf-8"))
        if not match:
            sys.exit(f"{dockerfile.relative_to(ROOT)} does not name a shared base (ARG SIDECAR_BASE=picsou-sidecar-…)")
        sidecars.append({"service": dockerfile.parent.name, "base": match.group(1)})
    return sidecars


def publish_matrix(repository):
    app = {"name": "app", "image": repository, "context": ".", "dockerfile": "docker/Dockerfile", "versioned": True}
    return [app] + [
        {
            "name": sidecar["service"],
            "image": f"{repository}/{sidecar['service']}",
            "context": f"services/{sidecar['service']}",
            "dockerfile": f"services/{sidecar['service']}/Dockerfile",
            "base": sidecar["base"],
            "versioned": False,
        }
        for sidecar in inventory()
    ]


# Camoufox ships its own Firefox build per architecture: those sidecars also run on
# a native ARM64 runner, which QEMU cannot stand in for.
RUNNERS = {"x86_64": "ubuntu-latest", "aarch64": "ubuntu-24.04-arm"}


def select(every, files):
    """The CI matrix legs for the sidecars a change can affect: those whose folder
    changed, or all of them when the change is elsewhere (services/base, .github)."""
    folder = re.compile(r"services/([^/]+-auth)/.+")
    matches = [folder.fullmatch(path) for path in files]
    changed = None if every or not all(matches) else {match.group(1) for match in matches}
    return [
        {**sidecar, "arch": arch, "runner": runner}
        for sidecar in inventory()
        if changed is None or sidecar["service"] in changed
        for arch, runner in RUNNERS.items()
        if arch == "x86_64" or sidecar["base"] == "camoufox"
    ]


def main(args):
    if not args:
        print(json.dumps(inventory()))
    elif args == ["--publish"]:
        print(json.dumps(publish_matrix(os.environ["GITHUB_REPOSITORY"])))
    elif args == ["--select"]:
        selected = select(os.environ.get("ALL") == "true", json.loads(os.environ.get("FILES") or "[]"))
        jobs = [job for job in json.loads(os.environ.get("CHANGES") or "[]") if job != "workflows"]
        if not selected:
            jobs = [job for job in jobs if job != "sidecar"]
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"jobs={json.dumps(jobs)}\nmatrix={json.dumps(selected)}\n")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
