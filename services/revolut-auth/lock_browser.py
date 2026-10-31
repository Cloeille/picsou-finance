"""Lock the installed Camoufox out of the local filesystem, through its enterprise policies.

During a captcha hand-off a member drives this browser by hand (viewer.py). It runs as the one
`revoauth` user that owns every member's profile, so any way to open a local file would let that
member read another member's Revolut session (cookies.sqlite, storage) or the sidecar itself. The
policies below remove each such way: no file picker (upload, Open File, Save As), no file: URL
(also through view-source:), no developer tools or about:config (chrome-privileged code, unlocking
prefs), no printing (the print dialog has a file chooser of its own). Firefox applies policies
before any page loads and the browser UI cannot change them.

Run once at image build, after the browser is installed (pin_camoufox.py). Camoufox ships its own
distribution/policies.json; it is kept, and these entries are added on top.
"""
import json
import os

from camoufox.pkgman import camoufox_path

LOCKED = {
    "DisableDeveloperTools": True,
    "BlockAboutConfig": True,
    "BlockAboutProfiles": True,
    "BlockAboutSupport": True,
    "PrintingEnabled": False,
}
LOCKED_PREFERENCES = {"widget.disable_file_pickers": {"Value": True, "Status": "locked"}}
BLOCKED_URLS = ["file:///*"]

path = os.path.join(camoufox_path(download_if_missing=False), "distribution", "policies.json")
with open(path) as f:
    document = json.load(f)
policies = document["policies"]
policies.update(LOCKED)
policies.setdefault("Preferences", {}).update(LOCKED_PREFERENCES)
website_filter = policies.setdefault("WebsiteFilter", {})
website_filter["Block"] = website_filter.get("Block", []) + BLOCKED_URLS
with open(path, "w") as f:
    json.dump(document, f, indent=2)
