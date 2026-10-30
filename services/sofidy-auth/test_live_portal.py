"""Runs the sidecar's own login and parse against the real Sofidy portal.

Fixtures written from the live page prove the parser handles a page. They cannot
prove the sidecar still logs in, that the endpoint paths are the ones Sofidy
serves, or that the table has not moved -- which is exactly what broke on CORUM,
three times, and what only a live run catches. This test is the one that fails
before the first bugfix commit.

The credential comes from the environment, never from the repository:

    SOFIDY_ASSOCIATE_CODE=<your six-digit code> SOFIDY_PASSWORD=... \\
    SOFIDY_2FA_CODE=<code from your inbox> python test_live_portal.py

Every figure and identifier in this repository's fixtures is invented. Nothing
captured from a real account -- the associate code, the holder's name, the fund
names, the quantities and the prices -- belongs in a committed file.

It is a script rather than a unittest: it costs a real login and a real
verification email, so it must never run in CI, where `ci.yml` mounts only
`test_positions_parser.py` and `test_live_contract.py`.
"""

import asyncio
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import main as service  # noqa: E402
from positions_parser import parse_portfolio  # noqa: E402


async def run() -> int:
    code = os.environ.get("SOFIDY_ASSOCIATE_CODE")
    password = os.environ.get("SOFIDY_PASSWORD")
    otp = os.environ.get("SOFIDY_2FA_CODE")
    if not (code and password and otp):
        print("SKIP: SOFIDY_ASSOCIATE_CODE / SOFIDY_PASSWORD / SOFIDY_2FA_CODE unset")
        return 0

    client = service._new_client()
    try:
        # 1. The portal issues PHPSESSID on the first page view, so the login
        #    has to follow a page fetch or the POST lands on a fresh session.
        home = await service._home(client)
        print(f"GET  {service.HOME_PATH} -> {service._is_logged_in(home) and 'logged in' or 'login form'}")

        # 2. The login, exactly as the site's own general.js does it.
        raw = (
            await service._post(client, service.LOGIN_PATH, {"user": code, "pass": password})
        ).strip()
        outcome = service._classify_login(raw)
        print(f"POST {service.LOGIN_PATH} -> {outcome if outcome else 'ok, no known code'}")
        if outcome is not None:
            print(f"FAIL: the portal answered {outcome[0]} instead of asking for a code")
            return 1
        if not service._is_logged_in(await service._home(client)):
            print("NOTE: no verification code was asked for; the session is already open")

        # 3. The verification code, on the same session.
        verified = (
            await service._post(
                client,
                service.VERIFY_2FA_PATH,
                {"user": code, "pass": password, "code": otp},
            )
        ).strip().lower()
        print(f"POST {service.VERIFY_2FA_PATH} -> {verified}")
        if verified not in ("fauxyes", "firstyes"):
            print("FAIL: the verification code was refused")
            return 1

        if not service._is_logged_in(await service._home(client)):
            print("FAIL: verification succeeded but no session was opened")
            return 1

        # 4. The portfolio, through the same parser the sidecar serves.
        response = await client.get(service.PORTFOLIO_PATH)
        print(f"GET  {service.PORTFOLIO_PATH} -> {response.status_code}")
        snapshot = parse_portfolio(response.text)

        print(f"valuation date: {snapshot['valuationDate']}")
        for holding in snapshot["holdings"]:
            print(
                f"  {holding['fundCode']:>4}  {holding['label']:<20} "
                f"{holding['shareCount']:>12} x {holding['withdrawalPriceEur']:>9} = "
                f"{holding['totalEur']:>10}"
            )
        if not snapshot["holdings"]:
            print("NOTE: an empty portfolio is a real state, not a failure")
        return 0
    finally:
        await client.aclose()


if __name__ == "__main__":
    raise SystemExit(asyncio.run(run()))
