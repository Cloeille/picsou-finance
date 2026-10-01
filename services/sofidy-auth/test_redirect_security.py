"""Real httpx redirects: stop before sending passwords, OTPs or cookies elsewhere."""
import json
import logging
import unittest
from unittest.mock import patch

import httpx
from fastapi.testclient import TestClient

import main
from test_live_contract import FAKE_SESSION, FUND_ROW, TOTAL_ROW, portfolio_html


class RedirectSecurityTest(unittest.TestCase):
    def request_with_redirect(self, route, target, status=307, loop=False):
        sent = []
        original_client = httpx.AsyncClient

        def answer(request):
            sent.append(request)
            if request.url.path == main.HOME_PATH:
                return httpx.Response(200, text="logged in")
            redirect_path = main.LOGIN_PATH if route == "/initiate" else (
                main.VERIFY_2FA_PATH if route == "/complete" else main.PORTFOLIO_PATH)
            if request.url.path == redirect_path or loop:
                return httpx.Response(status, headers={"Location": target})
            if route == "/positions":
                return httpx.Response(200, text=portfolio_html(FUND_ROW, TOTAL_ROW))
            return httpx.Response(200, text="2FA|fixture" if route == "/initiate" else "fauxyes")

        def factory(**kwargs):
            kwargs["transport"] = httpx.MockTransport(answer)
            return original_client(**kwargs)

        with patch.object(main.httpx, "AsyncClient", side_effect=factory):
            with TestClient(main.app) as app:
                if route == "/complete":
                    upstream = main._new_client()
                    main._pending["fixture"] = {
                        "client": upstream, "associateCode": "123456",
                        "password": "fixture-password", "created_at": main.time.time()}
                    body = {"processId": "fixture", "code": "654321"}
                elif route == "/positions":
                    body = {"sessionState": FAKE_SESSION}
                else:
                    body = {"associateCode": "123456", "password": "fixture-password"}
                response = app.post(route, json=body)
        return response, sent

    def test_307_and_308_never_forward_to_an_unsafe_origin(self):
        targets = ("https://attacker.example/steal?token=secret-query",
                   "http://moncompte.sofidy.com/steal", "http://127.0.0.1/steal",
                   "https://192.168.1.1/steal", "https://[::1]/steal",
                   "//attacker.example/steal", "https://moncompte.sofidy.com:444/steal",
                   "https://user:secret@moncompte.sofidy.com/steal")
        for route in ("/initiate", "/complete", "/positions"):
            for status in (307, 308):
                for target in targets:
                    with self.subTest(route=route, status=status, target=target):
                        response, sent = self.request_with_redirect(route, target, status)
                        self.assertEqual(response.status_code, 502)
                        self.assertTrue(all(r.url.host == "moncompte.sofidy.com" and
                                            r.url.scheme == "https" and
                                            r.url.port in (None, 443) and
                                            not r.url.username for r in sent))
                        self.assertFalse(any(r.url.path == "/steal" for r in sent))

    def test_relative_and_same_origin_https_redirects_still_work(self):
        for target in ("/safe", main.BASE_URL + "/safe"):
            for route in ("/initiate", "/complete", "/positions"):
                with self.subTest(route=route, target=target):
                    response, sent = self.request_with_redirect(route, target)
                    self.assertEqual(response.status_code, 200)
                    redirected = next(r for r in sent if r.url.path == "/safe")
                    if route != "/positions":
                        self.assertIn(b"fixture-password", redirected.content)
                    if route == "/complete":
                        self.assertIn(b"654321", redirected.content)

    def test_redirect_loops_are_bounded(self):
        response, sent = self.request_with_redirect("/initiate", "/loop", loop=True)
        self.assertEqual(response.status_code, 502)
        self.assertLessEqual(len(sent), 7)  # home + original request + at most five hops

    def test_raw_login_answers_and_redirect_targets_are_not_logged(self):
        with self.assertLogs(level=logging.INFO) as captured:
            self.request_with_redirect("/initiate", "https://attacker.example/secret-query")
        text = "\n".join(captured.output)
        for secret in ("fixture-password", "secret-query", "attacker.example"):
            self.assertNotIn(secret, text)


if __name__ == "__main__":
    unittest.main()
