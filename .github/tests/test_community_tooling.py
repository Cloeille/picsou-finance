"""Tests for issue forms, AI policy links, and the stale workflow."""

from datetime import datetime, timedelta, timezone
import importlib.util
from pathlib import Path
import re
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
ISSUE_TEMPLATE_DIR = ROOT / ".github/ISSUE_TEMPLATE"
STALE_WORKFLOW = ROOT / ".github/workflows/stale.yml"
SHA_PIN = re.compile(r"^[^@]+@[0-9a-f]{40}$")
VALID_FORM_TYPES = {"markdown", "input", "textarea", "dropdown", "checkboxes"}

_spec = importlib.util.spec_from_file_location("stale_prs", ROOT / ".github/scripts/stale_prs.py")
stale_prs = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(stale_prs)


def _load_yaml(path):
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def _pr(*, updated_at, author_association="CONTRIBUTOR", user_type="User", draft=False, labels=()):
    return {
        "number": 42,
        "draft": draft,
        "author_association": author_association,
        "user": {"login": "jane-doe", "type": user_type},
        "updated_at": updated_at.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "labels": [{"name": name} for name in labels],
    }


class IssueTemplatesTest(unittest.TestCase):
    def test_config_disables_blank_issues_and_links_security_policy(self):
        config = _load_yaml(ISSUE_TEMPLATE_DIR / "config.yml")
        self.assertIs(config["blank_issues_enabled"], False)
        self.assertTrue(any("SECURITY.md" in link.get("url", "") for link in config.get("contact_links", [])))

    def test_all_issue_forms_follow_github_schema(self):
        forms = sorted(p for p in ISSUE_TEMPLATE_DIR.glob("*.yml") if p.name != "config.yml")
        self.assertEqual([p.name for p in forms], ["broken_bank_connector.yml", "bug_report.yml", "feature_idea.yml"])
        for path in forms:
            with self.subTest(form=path.name):
                form = _load_yaml(path)
                self.assertTrue(form.get("name") and form.get("description") and form.get("body"))
                seen_ids = set()
                for item in form["body"]:
                    kind = item.get("type")
                    self.assertIn(kind, VALID_FORM_TYPES)
                    self.assertIsInstance(item.get("attributes"), dict)
                    if kind == "markdown":
                        self.assertNotIn("id", item)
                        self.assertTrue(item["attributes"].get("value"))
                    else:
                        item_id = item.get("id")
                        self.assertTrue(item_id and item_id not in seen_ids)
                        seen_ids.add(item_id)
                        self.assertTrue(item["attributes"].get("label"))

    def test_bug_report_form_captures_required_triage_and_privacy_fields(self):
        by_id = {i["id"]: i for i in _load_yaml(ISSUE_TEMPLATE_DIR / "bug_report.yml")["body"] if "id" in i}
        self.assertEqual(by_id["install-type"]["attributes"]["options"], ["Docker", "Helm", "Desktop", "Other"])
        for field in ("version", "install-type", "steps", "expected", "actual"):
            self.assertIs(by_id[field]["validations"]["required"], True)
        logs_desc = by_id["logs"]["attributes"]["description"].lower()
        self.assertIn("personal", logs_desc)
        self.assertIn("bank data", logs_desc)
        self.assertTrue(by_id["privacy"]["attributes"]["options"][0]["required"])

    def test_broken_bank_connector_form_warns_against_sensitive_data(self):
        form = _load_yaml(ISSUE_TEMPLATE_DIR / "broken_bank_connector.yml")
        by_id = {i["id"]: i for i in form["body"] if "id" in i}
        self.assertEqual(by_id["install-type"]["attributes"]["options"], ["Docker", "Helm", "Desktop", "Other"])
        for field in ("connector", "install-type", "version", "error-code"):
            self.assertIs(by_id[field]["validations"]["required"], True)
        markdown = "\n".join(i["attributes"]["value"] for i in form["body"] if i["type"] == "markdown").lower()
        for word in ("credentials", "account numbers", "balances", "session tokens", "screenshots"):
            self.assertIn(word, markdown)
        self.assertTrue(by_id["privacy"]["attributes"]["options"][0]["required"])

    def test_feature_idea_form_requires_prior_agreement_before_pr(self):
        form = _load_yaml(ISSUE_TEMPLATE_DIR / "feature_idea.yml")
        by_id = {i["id"]: i for i in form["body"] if "id" in i}
        markdown = "\n".join(i["attributes"]["value"] for i in form["body"] if i["type"] == "markdown").lower()
        self.assertIn("before", markdown)
        self.assertIn("pull request", markdown)
        self.assertTrue(by_id["agreement"]["attributes"]["options"][0]["required"])


class AiPolicyTest(unittest.TestCase):
    def test_ai_policy_exists_and_is_linked_from_contributing(self):
        policy = (ROOT / "AI_POLICY.md").read_text(encoding="utf-8").lower()
        self.assertIn("AI_POLICY.md", (ROOT / "CONTRIBUTING.md").read_text(encoding="utf-8"))
        for phrase in ("welcome", "explain every", "disclose", "previously merged", "without a detailed review", "synthetic fixtures"):
            self.assertIn(phrase, policy)


class StaleWorkflowTest(unittest.TestCase):
    def test_workflow_pins_actions_by_sha_and_scopes_issues_to_needs_info(self):
        workflow = _load_yaml(STALE_WORKFLOW)
        self.assertEqual(workflow["permissions"], {"contents": "read"})
        job = workflow["jobs"]["stale"]
        self.assertEqual(job["permissions"], {"contents": "read", "issues": "write", "pull-requests": "write"})
        steps = job["steps"]
        for step in steps:
            if "uses" in step:
                self.assertRegex(step["uses"], SHA_PIN)
        opts = next(s for s in steps if s.get("uses", "").startswith("actions/stale@"))["with"]
        self.assertEqual(opts["only-issue-labels"], "needs-info")
        self.assertEqual(opts["days-before-issue-stale"], 21)
        self.assertEqual(opts["days-before-issue-close"], 9)
        self.assertEqual(opts["days-before-pr-stale"], -1)
        self.assertEqual(opts["days-before-pr-close"], -1)
        self.assertIs(opts["remove-pr-stale-when-updated"], False)
        self.assertEqual(opts["stale-issue-label"], "stale")
        checkout = next(s for s in steps if s.get("uses", "").startswith("actions/checkout@"))
        self.assertIs(checkout["with"]["persist-credentials"], False)


class StaleExternalPrsTest(unittest.TestCase):
    NOW = datetime(2026, 6, 15, 3, 17, tzinfo=timezone.utc)

    def test_maintainer_and_bot_prs_are_never_touched(self):
        old = self.NOW - timedelta(days=120)
        for association in ("OWNER", "MEMBER", "COLLABORATOR"):
            for draft in (False, True):
                with self.subTest(association=association, draft=draft):
                    self.assertEqual(stale_prs.decide(_pr(updated_at=old, author_association=association, draft=draft), self.NOW), "skip")
        self.assertEqual(stale_prs.decide(_pr(updated_at=old, author_association="NONE", user_type="Bot"), self.NOW), "skip")
        self.assertEqual(stale_prs.decide(_pr(updated_at=old, author_association=None), self.NOW), "skip")
        self.assertEqual(stale_prs.decide(_pr(updated_at=old, user_type="Organization"), self.NOW), "skip")

    def test_external_pr_and_draft_become_stale_at_60_days_and_close_after_7(self):
        for draft in (False, True):
            with self.subTest(draft=draft):
                self.assertEqual(stale_prs.decide(_pr(updated_at=self.NOW - timedelta(days=59, hours=23), draft=draft), self.NOW), "skip")
                self.assertEqual(stale_prs.decide(_pr(updated_at=self.NOW - timedelta(days=60), draft=draft), self.NOW), "mark_stale")
                marked_6d = self.NOW - timedelta(days=6, hours=23)
                marked_7d = self.NOW - timedelta(days=7)
                self.assertEqual(stale_prs.decide(_pr(updated_at=marked_6d, labels=("stale",), draft=draft), self.NOW, marked_at=marked_6d), "skip")
                self.assertEqual(stale_prs.decide(_pr(updated_at=marked_7d + timedelta(seconds=5), labels=("stale",), draft=draft), self.NOW, marked_at=marked_7d), "close")

    def test_activity_after_warning_unstales_pr(self):
        marked = self.NOW - timedelta(days=3)
        self.assertEqual(stale_prs.decide(_pr(updated_at=marked + timedelta(hours=2), labels=("stale",)), self.NOW, marked_at=marked), "unstale")

    def test_latest_warning_time_picks_newest_bot_marker_comment(self):
        first, second = self.NOW - timedelta(days=90), self.NOW - timedelta(days=2)
        comments = [
            {"user": {"login": "github-actions[bot]"}, "body": f"{stale_prs.MARKER}\nfirst", "created_at": first.strftime("%Y-%m-%dT%H:%M:%SZ")},
            {"user": {"login": "jane-doe"}, "body": f"{stale_prs.MARKER}\nspoofed", "created_at": self.NOW.strftime("%Y-%m-%dT%H:%M:%SZ")},
            {"user": {"login": "github-actions[bot]"}, "body": "unrelated", "created_at": self.NOW.strftime("%Y-%m-%dT%H:%M:%SZ")},
            {"user": {"login": "github-actions[bot]"}, "body": f"{stale_prs.MARKER}\nsecond", "created_at": second.strftime("%Y-%m-%dT%H:%M:%SZ")},
        ]
        self.assertEqual(stale_prs.latest_warning_time(comments), second)

    def test_manual_stale_label_without_warning_comment_uses_updated_at(self):
        self.assertEqual(stale_prs.decide(_pr(updated_at=self.NOW - timedelta(days=3), labels=("stale",)), self.NOW), "skip")
        self.assertEqual(stale_prs.decide(_pr(updated_at=self.NOW - timedelta(days=7), labels=("stale",)), self.NOW), "close")


if __name__ == "__main__":
    unittest.main()
