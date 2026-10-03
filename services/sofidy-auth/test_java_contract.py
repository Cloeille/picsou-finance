"""Export the actual /positions HTTP JSON for the opt-in Java adapter test.

Run with the sidecar's pinned requirements. Only the upstream transport is fake;
parser, app route, Pydantic response filtering and JSON serialization are real.
"""
import os
import sys
from unittest.mock import patch

import httpx
from fastapi.testclient import TestClient

# The standalone fixture process has its own test-only key, never a production default.
os.environ["APP_SIDECAR_API_KEY"] = "test-key"

import main
from test_live_contract import FAKE_SESSION, FUND_ROW, TOTAL_ROW, portfolio_html


def response_json(scenario):
    row, total = FUND_ROW, TOTAL_ROW
    if scenario == "empty":
        row, total = "", TOTAL_ROW.replace("3.00000", "0").replace("300.00", "0.00")
    elif scenario == "unpriced":
        row = row.replace("100.00", "")
    elif scenario == "fees":
        # Displayed row total need not be quantity x redemption price. The
        # adapter must not substitute this fee-inclusive figure for that price.
        row, total = row.replace("300.00", "330.00"), total.replace("300.00", "330.00")
    elif scenario != "priced":
        raise ValueError("unknown fixture")
    original = httpx.AsyncClient

    def answer(request):
        if request.url.path == main.HOME_PATH:
            return httpx.Response(200, text="logged in")
        if request.url.path == main.PORTFOLIO_PATH:
            return httpx.Response(200, text=portfolio_html(row, total))
        return httpx.Response(404)

    def factory(**kwargs):
        return original(transport=httpx.MockTransport(answer), **kwargs)

    with patch.object(main.httpx, "AsyncClient", side_effect=factory):
        with TestClient(main.app) as client:
            response = client.post(
                "/positions",
                json={"sessionState": FAKE_SESSION},
                headers={"X-Picsou-Sidecar-Key": "test-key"},
            )
            response.raise_for_status()
            return response.text


if __name__ == "__main__":
    print(response_json(sys.argv[1]))
