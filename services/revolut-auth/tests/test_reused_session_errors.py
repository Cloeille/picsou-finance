"""A reused session that fails to harvest keeps the session and never starts a login.

Run: .venv/bin/python tests/test_reused_session_errors.py   (no pytest needed)
"""

import os
import sys
from unittest.mock import AsyncMock, patch

from fastapi import HTTPException
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import main  # noqa: E402


TEST_KEY = "revolut-sidecar-test-key"


def test_reused_session_harvest_errors_do_not_expire_session_or_start_login():
    member_id = "synthetic-harvest-error"
    profile_key = main._profile_key(member_id)
    try:
        with patch.dict(os.environ, {"APP_SIDECAR_API_KEY": TEST_KEY}), TestClient(main.app) as client:
            for allow_login in (False, True):
                for code in ("INVALID_DATA", "UPSTREAM_UNAVAILABLE", "PORTFOLIO_INCOMPLETE"):
                    with patch.object(main, "_harvest_from_profile", new=AsyncMock(
                        side_effect=HTTPException(status_code=502, detail=code)
                    )), patch.object(main, "_open_camoufox") as open_browser:
                        response = client.post("/sync", headers={"X-Picsou-Sidecar-Key": TEST_KEY},
                            json={"phoneNumber": "+336****0000", "passcode": "000000",
                                  "memberId": member_id, "allowLogin": allow_login})
                    assert response.status_code == 502, response.json()
                    assert response.json() == {"detail": code}
                    open_browser.assert_not_called()
                    assert not main._member_locks[profile_key].locked()
    finally:
        main._member_locks.pop(profile_key, None)
        main._progress.pop(profile_key, None)


if __name__ == "__main__":
    test_reused_session_harvest_errors_do_not_expire_session_or_start_login()
    print("PASS test_reused_session_harvest_errors_do_not_expire_session_or_start_login")
