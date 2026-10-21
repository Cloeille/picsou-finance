"""_new_browser goes through the shared lease and still releases its slot."""

import unittest
from unittest.mock import AsyncMock, MagicMock, patch

from fastapi import HTTPException

import main
from picsou_browser import BrowserCapacityError, BrowserUnavailableError


class LaunchChromiumPilotTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        main._browsers = 0
        main._leases.clear()

    async def test_new_browser_delegates_to_launch_chromium(self):
        context = AsyncMock()
        browser = AsyncMock()
        browser.new_context = AsyncMock(return_value=context)
        lease = MagicMock()
        lease.browser = browser
        lease.close = AsyncMock()
        with patch.object(main, "launch_chromium", new=AsyncMock(return_value=lease)) as launch:
            returned_browser, returned_context, collector = await main._new_browser(AsyncMock())
        launch.assert_awaited_once()
        self.assertEqual(launch.await_args.kwargs["args"], main.LAUNCH_ARGS)
        self.assertIs(launch.await_args.kwargs["headless"], True)
        self.assertIs(returned_browser, browser)
        self.assertIs(returned_context, context)
        self.assertIsInstance(collector, main.SessionCollector)
        self.assertEqual(main._browsers, 1)
        await main._close_resources(returned_context, returned_browser, None)
        lease.close.assert_awaited()
        self.assertEqual(main._browsers, 0)
        self.assertEqual(main._leases, {})

    async def test_capacity_error_is_503_and_releases_the_slot(self):
        with patch.object(
            main, "launch_chromium", new=AsyncMock(side_effect=BrowserCapacityError("full"))
        ):
            with self.assertRaises(HTTPException) as caught:
                await main._new_browser(AsyncMock())
        self.assertEqual(caught.exception.status_code, 503)
        self.assertEqual(caught.exception.detail, "UPSTREAM_UNAVAILABLE")
        self.assertEqual(main._browsers, 0)

    async def test_unavailable_error_is_502_and_releases_the_slot(self):
        with patch.object(
            main, "launch_chromium", new=AsyncMock(side_effect=BrowserUnavailableError("down"))
        ):
            with self.assertRaises(HTTPException) as caught:
                await main._new_browser(AsyncMock())
        self.assertEqual(caught.exception.status_code, 502)
        self.assertEqual(caught.exception.detail, "UPSTREAM_UNAVAILABLE")
        self.assertEqual(main._browsers, 0)

    async def test_unexpected_launch_failure_releases_the_slot(self):
        with patch.object(main, "launch_chromium", new=AsyncMock(side_effect=RuntimeError("boom"))):
            with self.assertRaises(RuntimeError):
                await main._new_browser(AsyncMock())
        self.assertEqual(main._browsers, 0)

    async def test_context_failure_releases_the_slot_and_the_lease(self):
        browser = AsyncMock()
        browser.new_context = AsyncMock(side_effect=RuntimeError("context"))
        lease = MagicMock()
        lease.browser = browser
        lease.close = AsyncMock()
        with patch.object(main, "launch_chromium", new=AsyncMock(return_value=lease)):
            with self.assertRaises(RuntimeError):
                await main._new_browser(AsyncMock())
        lease.close.assert_awaited()
        self.assertEqual(main._browsers, 0)

    async def test_route_failure_releases_the_slot_and_the_lease(self):
        context = AsyncMock()
        context.route = AsyncMock(side_effect=RuntimeError("route"))
        browser = AsyncMock()
        browser.new_context = AsyncMock(return_value=context)
        lease = MagicMock()
        lease.browser = browser
        lease.close = AsyncMock()
        with patch.object(main, "launch_chromium", new=AsyncMock(return_value=lease)):
            with self.assertRaises(RuntimeError):
                await main._new_browser(AsyncMock())
        lease.close.assert_awaited()
        self.assertEqual(main._browsers, 0)


if __name__ == "__main__":
    unittest.main()
