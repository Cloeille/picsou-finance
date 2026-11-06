"""Soft size nudge for pull requests: warn, never block.

Counts the lines a reviewer actually reads (added + deleted) and, above the budget in
CONTRIBUTING.md, keeps one comment on the pull request saying so. The comment is updated in
place on later pushes and never duplicated.

  pr_size.py   reads $GH_TOKEN, $REPO and $PR_NUMBER and calls the GitHub API with `gh`;
               the unit tests import counted() and render()

Excluded from the count: lockfiles, CHANGELOG.md, snapshots and fixtures.
"""

import os
import re
import subprocess
import sys

BUDGET = 600
MARKER = "<!-- pr-size-check -->"
BOT = "github-actions[bot]"
EXCLUDED = re.compile(
    r"(^|/)(bun\.lockb?|package-lock\.json|pnpm-lock\.yaml|yarn\.lock|poetry\.lock|Cargo\.lock|CHANGELOG\.md)$"
    r"|\.lock$|\.snap$|(^|/)(__snapshots__|fixtures)/"
)


def counted(files):
    """Changed lines in `files`, a list of (path, additions, deletions)."""
    return sum(added + deleted for path, added, deleted in files if not EXCLUDED.search(path))


def render(lines, repo):
    over = lines > BUDGET
    if over:
        verdict = (
            f"This pull request changes **{lines}** lines (lockfiles, `CHANGELOG.md`, snapshots and fixtures "
            f"excluded), above the soft budget of **{BUDGET}**. Smaller pull requests are reviewed and merged "
            "faster: consider a small refactor PR first, then the feature in slices that each build and "
            "pass on their own."
        )
    else:
        verdict = f"This pull request is now within the soft budget: {lines} of {BUDGET} changed lines."
    return (
        f"{MARKER}\n{verdict}\n\n"
        "This is a nudge, not a check that blocks: generated code or a mechanical rename can justify "
        "going over, say so in the description. See "
        f"[Before you open a pull request](https://github.com/{repo}/blob/main/CONTRIBUTING.md#before-you-open-a-pull-request)."
    )


def gh(*args):
    return subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout


def main():
    repo, number = os.environ["REPO"], os.environ["PR_NUMBER"]
    tsv = gh("api", "--paginate", f"repos/{repo}/pulls/{number}/files",
             "--jq", r'.[] | [.filename, .additions, .deletions] | @tsv')
    files = []
    for row in tsv.splitlines():
        path, added, deleted = row.rsplit("\t", 2)
        files.append((path, int(added), int(deleted)))
    lines = counted(files)

    existing = gh("api", "--paginate", f"repos/{repo}/issues/{number}/comments",
                  "--jq", f'.[] | select(.user.login == "{BOT}" and (.body | startswith("{MARKER}"))) | .id')
    comment_id = existing.split()[0] if existing.split() else None

    print(f"{lines} counted lines, budget {BUDGET}, existing comment: {comment_id}")
    if comment_id:
        gh("api", "--method", "PATCH", f"repos/{repo}/issues/comments/{comment_id}", "-f", f"body={render(lines, repo)}")
    elif lines > BUDGET:
        gh("api", "--method", "POST", f"repos/{repo}/issues/{number}/comments", "-f", f"body={render(lines, repo)}")


if __name__ == "__main__":
    sys.exit(main())
