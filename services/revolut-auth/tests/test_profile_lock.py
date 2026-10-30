"""Regression tests for the Camoufox profile-lock collision that made /sync 500.

A persistent Camoufox/Firefox profile can be opened by only ONE Firefox process at
a time. Two failure modes broke /sync with
"Firefox is already running, but is not responding":

  1. concurrent /sync calls for the same member launched two browsers on the same
     profile (the second died), and
  2. a browser that exited uncleanly left `lock`/`.parentlock` behind, wedging every
     later /sync until manual cleanup.

The fix is per-member serialization (fast-fail 409 when a sync is already running)
plus clearing stale Firefox lock files before each launch. These tests exercise both
without a real browser.

Run: .venv/bin/python tests/test_profile_lock.py   (no pytest needed)
"""

import os
import json
import sys
import tempfile

import anyio

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import main  # noqa: E402


async def test_concurrent_sync_for_same_member_fast_fails_409():
    """A second /sync for a member whose sync is already in flight must be rejected
    with 409 and must NOT reach the browser layer (which would collide on the profile)."""
    harvest_calls = 0
    started = anyio.Event()

    async def slow_harvest(member_id):
        nonlocal harvest_calls
        harvest_calls += 1
        started.set()
        await anyio.sleep(0.5)  # hold the member "busy" while the 2nd request arrives
        return {"accounts": []}

    original = main._harvest_from_profile
    main._harvest_from_profile = slow_harvest
    main._member_locks.clear()
    try:
        req = main.SyncRequest(phoneNumber="+33600000000", passcode="123456", memberId="1")
        results = {}

        async def first():
            results["first"] = await main.sync(req)

        async def second():
            await started.wait()          # ensure the first call already holds the lock
            results["second"] = await main.sync(req)

        async with anyio.create_task_group() as tg:
            tg.start_soon(first)
            tg.start_soon(second)

        assert harvest_calls == 1, f"expected exactly one harvest, got {harvest_calls}"
        second = results["second"]
        assert getattr(second, "status_code", None) == 409, (
            f"second concurrent sync should be 409, got {second!r}")
        assert results["first"] == {"accounts": []}, results["first"]
    finally:
        main._harvest_from_profile = original
        main._member_locks.clear()


async def test_lock_keyed_on_sanitized_profile_key():
    """The sync lock must key on the SAME canonical key as the profile dir. Raw ids that
    sanitize to the same profile (e.g. "1" and "1!") must share one lock, else two syncs
    would take different locks yet collide on one Firefox profile."""
    main._member_locks.clear()
    try:
        assert main._profile_key("1") == main._profile_key("1!") == "1"
        assert main._member_lock("1") is main._member_lock("1!"), "same profile must share a lock"
        assert main._member_lock("1") is main._member_lock("1"), "same id must reuse its lock"
        assert main._member_lock("1") is not main._member_lock("2"), "distinct profiles must differ"
    finally:
        main._member_locks.clear()


async def test_clear_stale_locks_removes_lock_files():
    """Stale `lock` (a symlink) and `.parentlock` in a profile dir must be removed, the
    profile dir itself kept, and a second call on a clean dir must be a no-op."""
    with tempfile.TemporaryDirectory() as root:
        original_root = main.PROFILES_ROOT
        main.PROFILES_ROOT = root
        try:
            profile = main._profile_dir("1")  # creates <root>/1
            os.symlink("127.0.1.1:+475", os.path.join(profile, "lock"))  # dangling, like Firefox
            open(os.path.join(profile, ".parentlock"), "w").close()
            (open(os.path.join(profile, "prefs.js"), "w")).close()  # real profile data — must survive

            main._clear_stale_locks("1")

            assert not os.path.lexists(os.path.join(profile, "lock"))
            assert not os.path.exists(os.path.join(profile, ".parentlock"))
            assert os.path.isdir(profile), "profile dir must be preserved"
            assert os.path.exists(os.path.join(profile, "prefs.js")), "profile data must be preserved"

            main._clear_stale_locks("1")  # idempotent, no raise on a clean dir
        finally:
            main.PROFILES_ROOT = original_root


async def test_progress_endpoint_reads_back_last_phase():
    """GET /progress/{member_id} must read back the last phase written by _set_progress
    (including extra kwargs like remainingSeconds), and return {"phase": None} for a
    member id with no recorded progress."""
    main._progress.clear()
    try:
        main._set_progress("1", "AWAITING_APPROVAL", remainingSeconds=255)

        result = await main.progress("1")
        assert result["phase"] == "AWAITING_APPROVAL", result
        assert result["remainingSeconds"] == 255, result

        assert await main.progress("999") == {"phase": None}
    finally:
        main._progress.clear()


async def test_sync_without_login_returns_session_expired_when_profile_is_dead():
    """Unattended scheduler syncs must not fall back to a fresh login/mobile approval.
    When the profile session cannot be reused, the sidecar should return the same flat
    401 shape the backend already understands."""
    original_harvest = main._harvest_from_profile

    async def dead_profile(member_id):
        return None

    main._harvest_from_profile = dead_profile
    main._member_locks.clear()
    try:
        req = main.SyncRequest(
            phoneNumber="+33600000000",
            passcode="123456",
            memberId="1",
            allowLogin=False,
        )

        result = await main.sync(req)

        assert getattr(result, "status_code", None) == 401, result
        assert json.loads(result.body) == {"error": "SESSION_EXPIRED"}
    finally:
        main._harvest_from_profile = original_harvest
        main._member_locks.clear()


async def test_browser_launch_failure_is_flat_503_not_asgi_exception():
    """A Camoufox launch failure during fresh login should be converted to a flat
    sidecar error instead of escaping through FastAPI as a stacktrace."""
    original_harvest = main._harvest_from_profile
    original_camoufox = main._camoufox

    async def dead_profile(member_id):
        return None

    class FailingCamoufox:
        async def __aenter__(self):
            raise main.BrowserLaunchError("boom")

        async def __aexit__(self, exc_type, exc, tb):
            return False

    main._harvest_from_profile = dead_profile
    main._camoufox = lambda member_id, headless: FailingCamoufox()
    main._member_locks.clear()
    try:
        req = main.SyncRequest(phoneNumber="+33600000000", passcode="123456", memberId="1")

        result = await main.sync(req)

        assert getattr(result, "status_code", None) == 503, result
        assert json.loads(result.body) == {"error": "BROWSER_LAUNCH_FAILED"}
    finally:
        main._harvest_from_profile = original_harvest
        main._camoufox = original_camoufox
        main._member_locks.clear()


async def test_launch_recovery_quarantines_profile_and_retries_once():
    """When an existing profile prevents browser launch, the sidecar quarantines it
    and retries once with a clean profile instead of leaving the member wedged."""
    with tempfile.TemporaryDirectory() as root:
        original_root = main.PROFILES_ROOT
        original_camoufox = main._camoufox
        main.PROFILES_ROOT = root
        entries = 0

        class SometimesFailingCamoufox:
            async def __aenter__(self):
                nonlocal entries
                entries += 1
                if entries == 1:
                    raise RuntimeError("profile is wedged")
                return "ctx"

            async def __aexit__(self, exc_type, exc, tb):
                return False

        try:
            profile = main._profile_dir("1")
            open(os.path.join(profile, "prefs.js"), "w").close()
            main._camoufox = lambda member_id, headless: SometimesFailingCamoufox()

            async with main._open_camoufox("1", headless=True, recover_profile=True) as ctx:
                assert ctx == "ctx"

            quarantine_root = os.path.join(root, ".quarantine")
            quarantined = os.listdir(quarantine_root)
            assert len(quarantined) == 1
            assert os.path.exists(os.path.join(quarantine_root, quarantined[0], "prefs.js"))
            assert entries == 2
        finally:
            main.PROFILES_ROOT = original_root
            main._camoufox = original_camoufox


async def _run():
    tests = [
        test_concurrent_sync_for_same_member_fast_fails_409,
        test_lock_keyed_on_sanitized_profile_key,
        test_clear_stale_locks_removes_lock_files,
        test_progress_endpoint_reads_back_last_phase,
        test_sync_without_login_returns_session_expired_when_profile_is_dead,
        test_browser_launch_failure_is_flat_503_not_asgi_exception,
        test_launch_recovery_quarantines_profile_and_retries_once,
    ]
    failures = 0
    for t in tests:
        try:
            await t()
            print(f"PASS {t.__name__}")
        except Exception as exc:  # noqa: BLE001
            failures += 1
            print(f"FAIL {t.__name__}: {type(exc).__name__}: {exc}")
    return failures


if __name__ == "__main__":
    sys.exit(1 if anyio.run(_run) else 0)
