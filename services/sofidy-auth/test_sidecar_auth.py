"""The Sofidy app keeps the shared sidecar-key contract."""

import logging
import time
import unittest
from unittest.mock import AsyncMock, patch

from fastapi import HTTPException
from picsou_sidecar import PendingStore

from picsou_sidecar import contract

import main


class SidecarAuthTest(contract.SidecarAuthContract):
    app = main.app


class PendingCapacityTest(unittest.IsolatedAsyncioTestCase):
    async def test_wrong_code_retries_only_when_the_attempt_can_be_parked(self):
        for full in (False, True):
            with self.subTest(full=full):
                store = PendingStore(dispose=main._dispose_pending_state, log=logging.getLogger("test"), max_size=1)
                upstream = AsyncMock()
                other = AsyncMock()
                state = {"client": upstream, "associateCode": "123456", "password": "synthetic", "created_at": time.time()}
                await store.put_or_raise("attempt", state)

                async def wrong_code(*args):
                    if full:
                        await store.put_or_raise("other", {"client": other, "created_at": time.time()})
                    return "wrong"

                try:
                    with patch.object(main, "_pending", store), patch.object(main, "_post", side_effect=wrong_code):
                        with self.assertRaises(HTTPException) as caught:
                            await main.complete(main.CompleteRequest(processId="attempt", code="123456"))
                    self.assertEqual(caught.exception.status_code, 503 if full else 401)
                    self.assertEqual(caught.exception.detail, "TOO_MANY_PENDING" if full else "MFA_INVALID")
                    if full:
                        self.assertNotIn("attempt", store)
                        upstream.aclose.assert_awaited_once()
                    else:
                        self.assertIn("attempt", store)
                        upstream.aclose.assert_not_awaited()
                finally:
                    await store.close_all()


if __name__ == "__main__":
    unittest.main()
