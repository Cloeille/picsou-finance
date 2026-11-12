"""Test doubles for Trade Republic: a fake api.traderepublic.com behind httpx.MockTransport."""

import base64
import json
from unittest.mock import patch

import httpx

import main

PROCESS_ID = "3f2b8c1e-9d4a-4b7e-8f60-2a1c5d9e7b03"
SUB = "subject-123"
REFRESH = "refresh-secret"
DEVICE = "device-secret"
SESSION = "session-secret"
PIN = "4321"
CLAIMS = {"sub": SUB, "act": {"acc": {"owner": "x"}}, "exp": 1}


def encode_claims(claims: dict, jwt_shaped: bool = False) -> str:
    payload = base64.urlsafe_b64encode(json.dumps(claims).encode()).decode().rstrip("=")
    return f"h.{payload}.s" if jwt_shaped else payload


def set_cookies(**cookies) -> list:
    return [("set-cookie", f"{name}={value}; Path=/api; Secure; HttpOnly") for name, value in cookies.items()]


def reply(status: int, body=None, cookies=None) -> httpx.Response:
    headers = set_cookies(**(cookies or {}))
    if body is None:
        return httpx.Response(status, headers=headers, content=b"")
    return httpx.Response(status, json=body, headers=headers)


class FakeTR:
    """Routes by (method, path). A route is a response, a list of responses
    (consumed in order, the last one repeats), or a callable(request) → response."""

    def __init__(self, routes: dict):
        self.routes = dict(routes)
        self.requests: list[httpx.Request] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        route = self.routes[(request.method, request.url.path)]
        if callable(route):
            return route(request)
        if isinstance(route, list):
            return route.pop(0) if len(route) > 1 else route[0]
        return route

    def calls(self, method: str, path: str) -> list[httpx.Request]:
        return [r for r in self.requests if (r.method, r.url.path) == (method, path)]

    def patcher(self):
        return patch.object(main, "new_client", lambda: httpx.AsyncClient(transport=httpx.MockTransport(self)))


V2_LOGIN = ("POST", "/api/v2/auth/web/login")
V1_LOGIN = ("POST", "/api/v1/auth/web/login")
V2_POLL = ("GET", f"/api/v2/auth/web/login/processes/{PROCESS_ID}")
V1_CODE = ("POST", f"/api/v1/auth/web/login/{PROCESS_ID}/1234")
V2_AUTHENTICATOR = ("POST", f"/api/v2/auth/web/login/processes/{PROCESS_ID}/authenticator-verification")
SESSION_ROUTE = ("POST", "/api/v2/auth/web/session")
EXPORT_REQUEST = ("POST", "/api/v1/portfolio-analytics/transactions/export/request")
EXPORT_STATUS = ("GET", "/api/v1/portfolio-analytics/transactions/export/status")
EXPORT_DOWNLOAD = ("GET", "/api/v1/portfolio-analytics/transactions/export/download")
JOB_ID = "job-4d1f-secret"


def confirmed(status: str = "CONFIRMED") -> httpx.Response:
    return reply(200, {"status": status, "claims": None, "trustDeviceRegistrationRequired": False},
                 cookies={"tr_session": SESSION, "tr_refresh": REFRESH, "tr_device": DEVICE,
                          "tr_claims": encode_claims(CLAIMS)})


def pending() -> httpx.Response:
    return reply(200, {"status": "PENDING", "requiredAction": "CONFIRM_IN_APP", "expiresAt": None, "claims": None})


def authenticator_pending() -> httpx.Response:
    return reply(200, {"status": "PENDING", "requiredAction": "AUTHENTICATOR_VERIFICATION",
                       "expiresAt": None, "claims": None})


def export_job(status: str = "PENDING", http_status: int = 200, seconds: int = 0) -> httpx.Response:
    return reply(http_status, {"jobId": JOB_ID, "status": status, "estimatedSecondsRemaining": seconds})


def csv_reply(content: bytes) -> httpx.Response:
    return httpx.Response(200, content=content, headers={"content-type": "text/csv;charset=UTF-8"})
