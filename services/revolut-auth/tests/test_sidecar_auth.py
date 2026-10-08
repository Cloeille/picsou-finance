"""HTTP-level tests for the Revolut sidecar's shared-key boundary.

Every path except /health must answer 401 with the Picsou-Sidecar-Key challenge
before routing, so the backend can tell a key mismatch from Revolut's own
SESSION_EXPIRED 401. A missing or blank key must refuse startup.

Run: .venv/bin/python tests/test_sidecar_auth.py   (no pytest needed)
"""

import os
import sys
from unittest.mock import AsyncMock, patch

from fastapi import HTTPException
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import main  # noqa: E402


TEST_KEY = "revolut-sidecar-test-key"
CHALLENGE = "Picsou-Sidecar-Key"


def _with_key(key):
    def decorate(test):
        def run():
            original = main.SIDECAR_API_KEY
            main.SIDECAR_API_KEY = key
            try:
                test()
            finally:
                main.SIDECAR_API_KEY = original
        run.__name__ = test.__name__
        return run
    return decorate


@_with_key(TEST_KEY)
def test_unauthenticated_and_invalid_key_requests_are_challenged_before_routing():
    requests = (
        ("GET", "/docs", None),
        ("GET", "/openapi.json", None),
        ("GET", "/not-a-route", None),
        ("GET", "/progress/1", None),
        ("POST", "/sync", {"phoneNumber": "+33600000000", "passcode": "123456", "memberId": "1"}),
        ("POST", "/sync", "{"),
    )
    invalid_headers = ({}, {"X-Picsou-Sidecar-Key": "wrong-key"}, {"X-Picsou-Sidecar-Key": ""})
    with TestClient(main.app) as client:
        for method, path, payload in requests:
            for headers in invalid_headers:
                if isinstance(payload, str):
                    response = client.request(method, path, headers=headers, content=payload)
                else:
                    response = client.request(method, path, headers=headers, json=payload)
                context = f"{method} {path} {headers}"
                assert response.status_code == 401, (context, response.status_code)
                assert response.json() == {"detail": "UNAUTHORIZED"}, (context, response.json())
                assert response.headers.get("WWW-Authenticate") == CHALLENGE, context


@_with_key(TEST_KEY)
def test_health_is_accessible_without_a_key():
    with TestClient(main.app) as client:
        response = client.get("/health")
    assert response.status_code == 200, response.status_code
    assert response.json() == {"status": "ok"}, response.json()


@_with_key(TEST_KEY)
def test_authorized_request_reaches_request_validation_without_a_challenge():
    main._progress.clear()
    try:
        with TestClient(main.app) as client:
            progress = client.get("/progress/1", headers={"X-Picsou-Sidecar-Key": TEST_KEY})
            invalid = client.post("/sync", json={}, headers={"X-Picsou-Sidecar-Key": TEST_KEY})
    finally:
        main._progress.clear()
    assert progress.status_code == 200, progress.status_code
    assert progress.json() == {"phase": None}, progress.json()
    assert invalid.status_code == 422, invalid.status_code
    assert "WWW-Authenticate" not in invalid.headers


@_with_key("clé")
def test_non_ascii_key_authenticates_as_utf8_wire_bytes():
    with TestClient(main.app) as client:
        response = client.get("/docs", headers=[(b"X-Picsou-Sidecar-Key", "clé".encode("utf-8"))])
    assert response.status_code == 200, response.status_code
    assert "WWW-Authenticate" not in response.headers


def test_startup_refuses_a_missing_or_blank_key():
    for missing_key in ("", "   "):
        @_with_key(missing_key)
        def start():
            try:
                with TestClient(main.app):
                    raise AssertionError("startup should refuse a missing key")
            except RuntimeError as exc:
                assert "APP_SIDECAR_API_KEY" in str(exc), str(exc)
        start()


@_with_key(TEST_KEY)
def test_reused_session_harvest_errors_do_not_expire_session_or_start_login():
    member_id = "synthetic-harvest-error"
    profile_key = main._profile_key(member_id)
    try:
        with TestClient(main.app) as client:
            for allow_login in (False, True):
                for code in ("INVALID_DATA", "UPSTREAM_UNAVAILABLE", "PORTFOLIO_INCOMPLETE"):
                    with patch.object(main, "_harvest_from_profile", new=AsyncMock(
                        side_effect=HTTPException(status_code=502, detail=code)
                    )), patch.object(main, "_open_camoufox") as open_browser:
                        response = client.post("/sync", headers={"X-Picsou-Sidecar-Key": TEST_KEY},
                            json={"phoneNumber": "+33600000000", "passcode": "000000",
                                  "memberId": member_id, "allowLogin": allow_login})
                    assert response.status_code == 502, response.json()
                    assert response.json() == {"detail": code}
                    open_browser.assert_not_called()
                    assert not main._member_locks[profile_key].locked()
    finally:
        main._member_locks.pop(profile_key, None)
        main._progress.pop(profile_key, None)


def _run():
    tests = [
        test_unauthenticated_and_invalid_key_requests_are_challenged_before_routing,
        test_health_is_accessible_without_a_key,
        test_authorized_request_reaches_request_validation_without_a_challenge,
        test_non_ascii_key_authenticates_as_utf8_wire_bytes,
        test_startup_refuses_a_missing_or_blank_key,
        test_reused_session_harvest_errors_do_not_expire_session_or_start_login,
    ]
    failures = 0
    for t in tests:
        try:
            t()
            print(f"PASS {t.__name__}")
        except Exception as exc:  # noqa: BLE001
            failures += 1
            print(f"FAIL {t.__name__}: {type(exc).__name__}: {exc}")
    return failures


if __name__ == "__main__":
    sys.exit(1 if _run() else 0)
