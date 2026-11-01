"""The AMEX app keeps the shared sidecar-key contract."""

import logging
import os
import time
import unittest
from unittest.mock import AsyncMock, MagicMock, patch

from fastapi import HTTPException
from fastapi.testclient import TestClient

from picsou_sidecar import KEY_HEADER, PendingStore, contract

import main


class SidecarAuthTest(contract.SidecarAuthContract):
    app = main.app


class InvalidBodyTest(unittest.TestCase):
    def test_authorised_invalid_body_uses_the_fixed_validation_envelope(self):
        with (
            patch.dict(os.environ, {"APP_SIDECAR_API_KEY": "test-key"}),
            TestClient(main.app) as client,
        ):
            response = client.post(
                "/accounts", json={"sessionState": {}},
                headers={KEY_HEADER: "test-key"},
            )
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json(), {"detail": "INVALID_DATA"})


class PendingAuthenticationLifecycleTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        # Isolate each test while exercising the real capacity and cleanup logic.
        self.store = PendingStore(
            dispose=main._dispose_pending_state,
            log=logging.getLogger("test"),
            max_size=main.MAX_PENDING,
        )
        pending = patch.object(main, "_pending", self.store)
        pending.start()
        self.addCleanup(pending.stop)
        self.addAsyncCleanup(self.store.close_all)
        self.context = MagicMock(close=AsyncMock())
        self.browser = MagicMock(close=AsyncMock())
        self.camoufox = MagicMock(__aexit__=AsyncMock())
        self.page = MagicMock()
        self.page.url = "https://synthetic.example/two-step-verification"
        self.page.title = AsyncMock(return_value="Verification")
        self.page.query_selector_all = AsyncMock(return_value=[])
        self.state = {
            "created_at": time.time(), "page": self.page,
            "context": self.context, "browser": self.browser,
            "camoufox": self.camoufox,
        }

    async def fill_pool(self):
        for index in range(main.MAX_PENDING):
            self.assertTrue(await self.store.put(str(index), {"created_at": time.time()}))

    def assert_resources_closed(self):
        self.context.close.assert_awaited_once()
        self.browser.close.assert_awaited_once()
        self.camoufox.__aexit__.assert_awaited_once_with(None, None, None)

    async def retry_wrong_otp(self, *, fill_pool):
        self.assertTrue(await self.store.put("attempt", self.state))
        otp_input = MagicMock(fill=AsyncMock(), input_value=AsyncMock(return_value="1234"))
        self.page.locator.return_value.count = AsyncMock(return_value=0)
        self.page.get_by_role.return_value.count = AsyncMock(return_value=0)

        async def submit(_key):
            # Other initiations can fill the slot while this attempt is taken.
            if fill_pool:
                await self.fill_pool()

        otp_input.press = AsyncMock(side_effect=submit)
        with (
            patch.object(main, "_find_otp_input", AsyncMock(return_value=otp_input)),
            patch.object(main, "OTP_RESULT_TIMEOUT_SECONDS", 0),
            patch.object(main, "_invalid_otp_visible", AsyncMock(return_value=True)),
            patch.object(main, "_log_verify_page_diagnostics", AsyncMock()),
        ):
            with self.assertRaises(HTTPException) as caught:
                await main.complete(main.CompleteRequest(processId="attempt", otp="1234"))
        self.assertEqual(caught.exception.status_code, 503 if fill_pool else 401)
        self.assertEqual(caught.exception.detail, "UPSTREAM_UNAVAILABLE" if fill_pool else "INVALID_OTP")

    async def test_retryable_otp_closes_resources_when_it_cannot_be_parked(self):
        await self.retry_wrong_otp(fill_pool=True)
        self.assertNotIn("attempt", self.store)
        self.assertEqual(len(self.store), main.MAX_PENDING)
        self.assert_resources_closed()

    async def test_retryable_otp_keeps_resources_when_it_can_be_parked(self):
        await self.retry_wrong_otp(fill_pool=False)
        self.assertIs(await self.store.take("attempt"), self.state)
        self.context.close.assert_not_awaited()
        self.browser.close.assert_not_awaited()
        self.camoufox.__aexit__.assert_not_awaited()
        await main._dispose_pending_state(self.state)

    async def test_initiate_returns_503_and_closes_resources_when_pool_is_full(self):
        await self.fill_pool()
        self.camoufox.__aenter__ = AsyncMock(return_value=self.browser)
        self.browser.new_context = AsyncMock(return_value=self.context)
        self.context.new_page = AsyncMock(return_value=self.page)
        self.page.goto = AsyncMock()
        field = self.page.locator.return_value.first
        field.wait_for = AsyncMock()
        field.press_sequentially = AsyncMock()
        field.input_value = AsyncMock(return_value="synthetic")
        field.fill = AsyncMock()
        field.click = AsyncMock()
        self.page.locator.return_value.click = AsyncMock()
        with (
            patch.object(main, "AsyncCamoufox", return_value=self.camoufox),
            patch.object(main, "_wait_for_login_outcome", AsyncMock(return_value="verify")),
            patch.object(main, "_select_otp_method", AsyncMock()),
            patch.object(main, "_find_otp_input", AsyncMock()),
        ):
            with self.assertRaises(HTTPException) as caught:
                await main.initiate(main.InitiateRequest(
                    userId="synthetic", password="synthetic", method="sms",
                ))
        self.assertEqual(caught.exception.status_code, 503)
        self.assertEqual(caught.exception.detail, "UPSTREAM_UNAVAILABLE")
        self.assertEqual(len(self.store), main.MAX_PENDING)
        self.assert_resources_closed()


if __name__ == "__main__":
    unittest.main()
