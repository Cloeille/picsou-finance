"""codeql.yml: which CodeQL languages a run analyses.

  codeql_languages.py   writes `matrix` (JSON list of {"language", "build-mode"}) to
                        $GITHUB_OUTPUT. $ALL is 'true' for push and schedule runs, and
                        for a pull request whose path filter did not run; $FILES is the
                        JSON list of the pull request's changed paths.

A pull request analyses only the languages its changed files can affect. Docs and
Markdown files affect none; a file no rule names selects every language.
"""

import json
import os
import sys

LANGUAGES = ["java-kotlin", "javascript-typescript", "python", "actions"]
PREFIXES = {
    "backend/": ["java-kotlin"],
    "frontend/": ["javascript-typescript"],
    ".design-sync/": ["javascript-typescript"],
    "services/": ["python"],
    "docker/": ["python"],
    "charts/": ["python"],
    ".github/": ["python", "actions"],
}


def languages(files):
    """The languages to analyse for these changed paths, or all of them when files is None."""
    if files is None:
        return list(LANGUAGES)
    selected = set()
    for path in files:
        if path.startswith("docs/") or path.endswith(".md"):
            continue
        for prefix, mapped in PREFIXES.items():
            if path.startswith(prefix):
                selected.update(mapped)
                break
        else:
            return list(LANGUAGES)
    return [language for language in LANGUAGES if language in selected]


def main():
    files = None if os.environ.get("ALL") == "true" else json.loads(os.environ.get("FILES") or "null")
    matrix = [{"language": language, "build-mode": "none"} for language in languages(files)]
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write(f"matrix={json.dumps(matrix)}\n")


if __name__ == "__main__":
    sys.exit(main())
