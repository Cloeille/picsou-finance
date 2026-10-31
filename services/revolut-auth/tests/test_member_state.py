"""Pinned browser identity and login-pacing files (member_state.py): no browser needed.

The identity is what makes every launch look like the same machine to Revolut; the failure
date is what keeps the sidecar from hammering a login that already failed today.
"""

import copy
import json
import os
from datetime import date

import pytest

import main
import member_state


@pytest.fixture
def path(tmp_path):
    return str(tmp_path / "7.fingerprint.json")


def _stored(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def _same_kwargs(a, b):
    return (a["fingerprint"] == b["fingerprint"] and a["webgl_config"] == b["webgl_config"]
            and a["config"] == b["config"] and a["locale"] == b["locale"] and a["os"] == b["os"])


# ─── identity: generated once, reused ────────────────────────────────────────

def test_first_launch_creates_the_identity_and_later_launches_reuse_it(path):
    first = member_state.launch_kwargs(path)
    written = open(path, "rb").read()
    second = member_state.launch_kwargs(path)

    assert _same_kwargs(first, second)
    assert open(path, "rb").read() == written, "a valid identity must not be rewritten"


def test_identity_is_linux_firefox_french_with_the_fixed_screen_and_timezone(path):
    kwargs = member_state.launch_kwargs(path)

    fingerprint = kwargs["fingerprint"]
    assert kwargs["os"] == "linux"
    assert kwargs["locale"] == "fr-FR"
    assert kwargs["config"]["timezone"] == "Europe/Paris"
    assert (fingerprint.screen.width, fingerprint.screen.height) == member_state.SCREEN
    assert "Firefox/" in fingerprint.navigator.userAgent
    assert fingerprint.navigator.platform.startswith("Linux")


def test_each_call_gets_its_own_config_dict_because_camoufox_mutates_it(path):
    first = member_state.launch_kwargs(path)
    first["config"]["injected"] = "by camoufox"

    assert "injected" not in member_state.launch_kwargs(path)["config"]


def test_members_get_different_identities(tmp_path):
    a = member_state.launch_kwargs(str(tmp_path / "1.fingerprint.json"))
    b = member_state.launch_kwargs(str(tmp_path / "2.fingerprint.json"))

    assert not _same_kwargs(a, b)


def test_an_unwritable_location_still_yields_an_identity_for_this_launch(path, monkeypatch):
    def refuse(_path, _text):
        raise OSError("read-only volume")

    monkeypatch.setattr(member_state, "_write_atomically", refuse)

    kwargs = member_state.launch_kwargs(path)

    assert kwargs["fingerprint"].screen.width == member_state.SCREEN[0]
    assert not os.path.exists(path)


def test_the_save_leaves_no_temporary_file_behind(path):
    member_state.launch_kwargs(path)

    assert os.listdir(os.path.dirname(path)) == [os.path.basename(path)]


# ─── a missing or corrupt file never breaks a launch ─────────────────────────

def _break(mutate):
    def corrupt(identity):
        broken = copy.deepcopy(identity)
        mutate(broken)
        return json.dumps(broken)
    return corrupt


CORRUPTIONS = {
    "empty file": lambda identity: "",
    "truncated json": lambda identity: json.dumps(identity)[:200],
    "not json": lambda identity: "<html>",
    "json list": lambda identity: "[]",
    "json null": lambda identity: "null",
    "no fingerprint": _break(lambda i: i.pop("fingerprint")),
    "fingerprint is a string": _break(lambda i: i.update(fingerprint="x")),
    "no navigator": _break(lambda i: i["fingerprint"].pop("navigator")),
    "unexpected navigator field": _break(lambda i: i["fingerprint"]["navigator"].update(bogus=1)),
    "another screen size": _break(lambda i: i["fingerprint"]["screen"].update(width=1280, height=720)),
    "not a linux browser": _break(lambda i: i["fingerprint"]["navigator"].update(platform="Win32")),
    "not firefox": _break(lambda i: i["fingerprint"]["navigator"].update(userAgent="Mozilla/5.0 Chrome/120")),
    "unknown webgl pair": _break(lambda i: i.update(webgl=["Nobody", "No such GPU"])),
    "webgl is not a pair": _break(lambda i: i.update(webgl="AMD")),
    "no canvas offset": _break(lambda i: i.pop("canvasAaOffset")),
    "canvas offset is not a number": _break(lambda i: i.update(canvasAaOffset="x")),
}


@pytest.mark.parametrize("name", CORRUPTIONS)
def test_a_corrupt_identity_is_replaced_by_a_fresh_valid_one(path, name):
    member_state.launch_kwargs(path)
    valid = _stored(path)
    with open(path, "w", encoding="utf-8") as f:
        f.write(CORRUPTIONS[name](valid))

    kwargs = member_state.launch_kwargs(path)

    assert kwargs["fingerprint"].screen.width == member_state.SCREEN[0]
    # The replacement was saved and is valid: the next launch reuses it unchanged.
    assert _stored(path)["fingerprint"]["screen"]["width"] == member_state.SCREEN[0]
    assert _same_kwargs(kwargs, member_state.launch_kwargs(path))


def test_a_missing_file_is_created(path):
    assert not os.path.exists(path)

    member_state.launch_kwargs(path)

    assert _stored(path)["fingerprint"]["screen"]["width"] == member_state.SCREEN[0]


# ─── login pacing ────────────────────────────────────────────────────────────

TODAY, YESTERDAY = date(2026, 10, 21), date(2026, 10, 20)


def test_a_recorded_failure_blocks_only_that_day(tmp_path):
    marker = str(tmp_path / "7.login-failed")
    assert not member_state.login_failed_on(marker, TODAY)

    member_state.record_login_failure(marker, TODAY)

    assert member_state.login_failed_on(marker, TODAY)
    assert not member_state.login_failed_on(marker, date(2026, 10, 22))
    assert member_state.login_failed_on(marker, TODAY), "reading must not consume the marker"


def test_clearing_removes_the_failure_and_is_idempotent(tmp_path):
    marker = str(tmp_path / "7.login-failed")
    member_state.record_login_failure(marker, TODAY)

    member_state.clear_login_failure(marker)
    member_state.clear_login_failure(marker)

    assert not member_state.login_failed_on(marker, TODAY)


def test_an_unreadable_marker_does_not_block_logins(tmp_path):
    marker = tmp_path / "7.login-failed"
    marker.write_bytes(b"\xff\xfe not a date")

    assert not member_state.login_failed_on(str(marker), TODAY)


# ─── where the files live ────────────────────────────────────────────────────

def test_state_files_sit_next_to_the_profile_and_survive_its_quarantine(profiles_root):
    profile = main._profile_dir("7")
    open(os.path.join(profile, "prefs.js"), "w").close()
    member_state.launch_kwargs(main._member_file("7", "fingerprint.json"))
    member_state.record_login_failure(main._member_file("7", "login-failed"), TODAY)

    main._quarantine_profile("7")

    assert os.listdir(profile) == [], "the quarantined profile is replaced by an empty one"
    assert os.path.exists(main._member_file("7", "fingerprint.json"))
    assert member_state.login_failed_on(main._member_file("7", "login-failed"), TODAY)
    assert not main._has_profile("7"), "state files must not make an empty profile look like a session"


def test_state_files_are_keyed_like_the_profile_so_unsafe_ids_cannot_escape(profiles_root):
    assert main._member_file("7", "login-failed") == main._member_file("7!", "login-failed")
    assert main._member_file("../../etc", "login-failed").startswith(str(profiles_root) + os.sep)


def test_a_state_file_name_that_climbs_out_of_the_profiles_directory_is_refused(profiles_root):
    with pytest.raises(ValueError, match="outside the profiles directory"):
        main._member_file("7", "/../../etc/passwd")


def test_a_path_with_a_line_break_cannot_forge_a_log_line(tmp_path, monkeypatch):
    forged = tmp_path / "7\nWARNING:revolut-auth:forged.fingerprint.json"
    forged.write_text("not json", encoding="utf-8")
    records = []
    monkeypatch.setattr(member_state.log, "handle", records.append)

    member_state.launch_kwargs(str(forged))

    (record,) = records
    assert "\n" not in record.getMessage()


# ─── the launch uses the pinned identity every time ──────────────────────────

def test_every_launch_gets_the_same_identity_headless_or_not(monkeypatch, profiles_root):
    launches = []

    class Recorder:
        def __init__(self, **kwargs):
            launches.append(kwargs)

    monkeypatch.setattr(main, "AsyncCamoufox", Recorder)

    main._camoufox("7", headless=True)
    main._camoufox("7", headless=False)
    main._camoufox("8", headless=False)

    headless, headful, other_member = launches
    assert _same_kwargs(headless, headful)
    assert not _same_kwargs(headless, other_member)
    assert (headless["headless"], headful["headless"]) == (True, False)
    assert headless["geoip"] is True and headless["persistent_context"] is True
    assert headless["locale"] == "fr-FR" and headless["os"] == "linux"
