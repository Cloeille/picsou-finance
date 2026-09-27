"""Runs /positions against the live portal with an already-open session.

Logging in needs a credential this process must not hold, and a verification
code delivered to an inbox. The browser already holds a live session, so this
reuses its exported cookies: it exercises the half of the sidecar that fixtures
cannot reach -- the real `3,clients.html` over real HTTPS, through the real
`serialize_cookies` / `restore_cookies` round-trip, into the real parser.

    python test_live_positions.py <path-to-session.json>
"""

import asyncio
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import main as service  # noqa: E402


async def run(session_path: str) -> int:
    with open(session_path) as handle:
        session_state = json.dumps(json.load(handle), separators=(",", ":"))

    client = service._new_client()
    try:
        service.restore_cookies(client, session_state)
        home = await service._home(client)
        if not service._is_logged_in(home):
            print("FAIL: the exported session is not logged in any more")
            return 1
        print(f"GET  {service.HOME_PATH} -> logged in")

        response = await client.get(service.PORTFOLIO_PATH)
        print(f"GET  {service.PORTFOLIO_PATH} -> {response.status_code}")

        # Through the route, not straight to the parser: this is the payload
        # Java will receive, so the response model is part of what is proven.
        snapshot = service.SnapshotPayload.model_validate(
            service.parse_portfolio(response.text)
        ).model_dump()

        print(f"valuation date: {snapshot['valuationDate']}")
        for holding in snapshot["holdings"]:
            print(
                f"  {holding['fundCode']:>4}  {holding['label']:<20} "
                f"{holding['shareCount']:>12} x {holding['withdrawalPriceEur']:>9} = "
                f"{holding['totalEur']:>10}"
            )
        print("OK")
        return 0
    finally:
        await client.aclose()


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        raise SystemExit(2)
    raise SystemExit(asyncio.run(run(sys.argv[1])))
