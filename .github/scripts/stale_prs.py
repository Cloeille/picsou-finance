"""Mark and close inactive pull requests opened by authors outside the maintainers.

actions/stale has no author-association filter (its assignee exemptions only inspect PR
assignees, not the PR author). This script handles external PRs alongside actions/stale's
`needs-info` issue handling in `.github/workflows/stale.yml`.

Rules:
- Maintainer PRs (`OWNER`, `MEMBER`, `COLLABORATOR`) and bot PRs (`user.type == "Bot"`,
  e.g. Dependabot) are never touched.
- External draft PRs are included (not exempted).
- After 60 days without activity, apply `stale` and post a warning comment.
- If updated after the warning comment, remove `stale`.
- If still inactive 7 days after the warning comment, close the PR.
"""

from datetime import datetime, timedelta, timezone
import json
import os
import subprocess
import sys

STALE_LABEL = "stale"
DAYS_BEFORE_STALE = 60
DAYS_BEFORE_CLOSE = 7
OPERATIONS_PER_RUN = 30
UNSTALE_GRACE = timedelta(seconds=60)
MAINTAINER_ASSOCIATIONS = frozenset({"OWNER", "MEMBER", "COLLABORATOR"})
MARKER = "<!-- stale-pr-warning -->"
BOT_LOGIN = "github-actions[bot]"

STALE_MESSAGE = (
    f"{MARKER}\n"
    f"This pull request has had no activity for {DAYS_BEFORE_STALE} days and has been marked stale. "
    "Push an update or leave a comment if you still plan to work on it; otherwise it will be closed "
    f"automatically in {DAYS_BEFORE_CLOSE} days."
)
CLOSE_MESSAGE = (
    f"Closing this pull request after {DAYS_BEFORE_CLOSE} days with no activity since it was marked stale. "
    "Feel free to reopen it or open a fresh pull request if you pick it up again."
)


def parse_timestamp(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)


def is_external_human_pr(pr):
    association = pr.get("author_association")
    return (
        bool(association)
        and association not in MAINTAINER_ASSOCIATIONS
        and (pr.get("user") or {}).get("type") == "User"
    )


def has_stale_label(pr):
    return any((label.get("name") or "").lower() == STALE_LABEL for label in pr.get("labels") or ())


def latest_warning_time(comments):
    times = [
        parse_timestamp(c["created_at"])
        for c in comments
        if (c.get("user") or {}).get("login") == BOT_LOGIN
        and (c.get("body") or "").startswith(MARKER)
        and c.get("created_at")
    ]
    return max(times) if times else None


def decide(pr, now, marked_at=None):
    """Return one of: 'skip', 'mark_stale', 'unstale', 'close'."""
    if not is_external_human_pr(pr):
        return "skip"
    updated_at = parse_timestamp(pr["updated_at"])
    if not has_stale_label(pr):
        return "mark_stale" if now - updated_at >= timedelta(days=DAYS_BEFORE_STALE) else "skip"
    if marked_at is not None:
        if updated_at > marked_at + UNSTALE_GRACE:
            return "unstale"
        return "close" if now - marked_at >= timedelta(days=DAYS_BEFORE_CLOSE) else "skip"
    return "close" if now - updated_at >= timedelta(days=DAYS_BEFORE_CLOSE) else "skip"


def gh(*args):
    try:
        return subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout
    except subprocess.CalledProcessError as exc:
        print(exc.stderr, file=sys.stderr)
        raise


def list_paginated(endpoint):
    pages = json.loads(gh("api", "--paginate", "--slurp", endpoint) or "[]")
    return [item for page in pages for item in page]


def main():
    repo = os.environ["REPO"]
    now = datetime.now(timezone.utc)
    pulls = list_paginated(f"repos/{repo}/pulls?state=open&per_page=100")
    operations = 0
    for pr in pulls:
        if operations >= OPERATIONS_PER_RUN:
            print(f"Reached operations-per-run cap ({OPERATIONS_PER_RUN}); stopping.")
            break
        if not is_external_human_pr(pr):
            continue
        number = pr["number"]
        marked_at = (
            latest_warning_time(list_paginated(f"repos/{repo}/issues/{number}/comments?per_page=100"))
            if has_stale_label(pr)
            else None
        )
        action = decide(pr, now, marked_at=marked_at)
        if action == "skip":
            continue
        print(f"PR #{number}: {action}")
        if action == "mark_stale":
            gh("api", "--method", "POST", f"repos/{repo}/issues/{number}/labels", "-f", f"labels[]={STALE_LABEL}")
            gh("api", "--method", "POST", f"repos/{repo}/issues/{number}/comments", "-f", f"body={STALE_MESSAGE}")
        elif action == "unstale":
            gh("api", "--method", "DELETE", f"repos/{repo}/issues/{number}/labels/{STALE_LABEL}")
        elif action == "close":
            gh("api", "--method", "POST", f"repos/{repo}/issues/{number}/comments", "-f", f"body={CLOSE_MESSAGE}")
            gh("api", "--method", "PATCH", f"repos/{repo}/pulls/{number}", "-f", "state=closed")
        operations += 1
    print(f"Processed {len(pulls)} open PRs; performed {operations} operations.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
