"""The shared sidecar package: pending store, log hygiene, app options.

The key contract itself runs against every sidecar app (picsou_sidecar.contract);
here it runs once against a bare app so the package is covered on its own.
"""

import asyncio
import io
import logging
import os
import time
import unittest
from unittest.mock import patch

from fastapi import HTTPException
from fastapi.testclient import TestClient
from pydantic import BaseModel

from picsou_sidecar import KEY_HEADER, PendingStore, SafeFormatter, create_app, otp_field_detail, safe
from picsou_sidecar import contract


class Body(BaseModel):
    code: str
    name: str


def _app(**kwargs):
    app = create_app(**kwargs)

    @app.post("/initiate")
    async def initiate(body: Body) -> dict:
        return {"ok": True}

    return app


class BareAppContractTest(contract.SidecarAuthContract):
    app = _app()


class LogSafetyTest(unittest.TestCase):
    def test_line_breaks_and_controls_cannot_forge_a_log_line(self):
        self.assertEqual(safe("a\r\nb\nc\rd"), "a?b?c?d")
        self.assertEqual(safe("a\u0085b\u2028c\u2029d"), "a?b?c?d")
        self.assertEqual(safe("\x1b[2Jx\x00\x7f"), "?[2Jx??")

    def test_long_values_are_bounded(self):
        self.assertEqual(safe("x" * 5000), "x" * 500 + "...")
        self.assertEqual(safe("x" * 300, 200), "x" * 200 + "...")

    def test_the_formatter_escapes_the_whole_record(self):
        record = logging.LogRecord("t", logging.INFO, __file__, 1, "path=%s", ("/a\nINFO:t:forged",), None)
        self.assertEqual(SafeFormatter("%(message)s").format(record), "path=/a\\nINFO:t:forged")


class FakeResource:
    def __init__(self):
        self.closed = 0


async def _dispose(state):
    state["resource"].closed += 1


def _store(**kwargs):
    return PendingStore(dispose=_dispose, log=logging.getLogger("test"), **kwargs)


class PendingStoreTest(unittest.IsolatedAsyncioTestCase):
    async def test_take_returns_the_state_once(self):
        store = _store()
        state = {"created_at": time.time(), "resource": FakeResource()}
        self.assertTrue(await store.put("p", state))
        self.assertIs(await store.take("p"), state)
        self.assertIsNone(await store.take("p"))
        self.assertEqual(state["resource"].closed, 0)

    async def test_take_disposes_expired_state_without_a_sweep(self):
        store = _store(ttl_seconds=60)
        resource = FakeResource()
        with patch("picsou_sidecar.pending.time.time", return_value=100):
            await store.put("p", {"created_at": 100, "resource": resource})
        with patch("picsou_sidecar.pending.time.time", return_value=161):
            self.assertIsNone(await store.take("p"))
        self.assertIsNone(await store.take("p"))
        self.assertEqual(resource.closed, 1)

    async def test_put_reclaims_expired_capacity(self):
        store = _store(ttl_seconds=60, max_size=1)
        resource = FakeResource()
        await store.put("old", {"created_at": 0, "resource": resource})
        self.assertTrue(await store.put("new", {"created_at": time.time()}))
        self.assertEqual(resource.closed, 1)
        self.assertNotIn("old", store)
        self.assertIn("new", store)

    async def test_put_or_raise_preserves_detail_and_caller_resource_ownership(self):
        store = _store(max_size=1)
        await store.put_or_raise("a", {"created_at": time.time()})
        resource = FakeResource()
        for detail in ("UPSTREAM_UNAVAILABLE", "TOO_MANY_PENDING"):
            with self.subTest(detail=detail), self.assertRaises(HTTPException) as raised:
                await store.put_or_raise("b", {"created_at": time.time(), "resource": resource}, detail)
            self.assertEqual(raised.exception.status_code, 503)
            self.assertEqual(raised.exception.detail, detail)
        self.assertEqual(resource.closed, 0)
        self.assertNotIn("b", store)

    async def test_a_full_store_refuses_without_storing(self):
        store = _store(max_size=1)
        self.assertTrue(await store.put("a", {"created_at": time.time()}))
        self.assertFalse(await store.put("b", {"created_at": time.time()}))
        self.assertNotIn("b", store)
        self.assertEqual(len(store), 1)

    async def test_expired_states_are_disposed_and_fresh_ones_kept(self):
        store = _store(ttl_seconds=60)
        old, fresh = FakeResource(), FakeResource()
        old_state = {"created_at": time.time(), "resource": old}
        await store.put("old", old_state)
        await store.put("fresh", {"created_at": time.time(), "resource": fresh})
        old_state["created_at"] -= 61
        await store.expire()
        self.assertEqual((old.closed, fresh.closed), (1, 0))
        self.assertEqual(len(store), 1)

    async def test_close_and_close_all_dispose(self):
        store = _store()
        one, two = FakeResource(), FakeResource()
        await store.put("one", {"created_at": time.time(), "resource": one})
        await store.put("two", {"created_at": time.time(), "resource": two})
        await store.close("one")
        await store.close("missing")
        self.assertEqual((one.closed, two.closed), (1, 0))
        await store.close_all()
        self.assertEqual((one.closed, two.closed), (1, 1))
        self.assertEqual(len(store), 0)

    async def test_a_failing_sweep_does_not_end_the_sweeper(self):
        store = _store(sweep_seconds=0)
        calls = 0

        async def expire():
            nonlocal calls
            calls += 1
            raise RuntimeError("sweep failed")

        store.expire = expire
        with self.assertLogs("test", logging.WARNING):
            task = asyncio.create_task(store.sweeper())
            while calls < 3:
                await asyncio.sleep(0)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task

    async def test_disposal_failure_keeps_unvisited_states_for_later_cleanup(self):
        for operation in ("expire", "close_all"):
            with self.subTest(operation=operation):
                async def dispose(state):
                    if state["resource"] is first:
                        raise RuntimeError("cleanup failed")
                    await _dispose(state)

                store = PendingStore(dispose=dispose, log=logging.getLogger("test"))
                first, second = FakeResource(), FakeResource()
                for pid, resource in (("first", first), ("second", second)):
                    await store.put(pid, {"created_at": time.time(), "resource": resource})
                for state in store._states.values():
                    state["created_at"] = 0
                with self.assertRaisesRegex(RuntimeError, "cleanup failed"):
                    await getattr(store, operation)()
                self.assertIn("second", store)
                self.assertEqual(second.closed, 0)
                await store.close_all()
                self.assertEqual(second.closed, 1)
                self.assertEqual(len(store), 0)

    async def test_cancelled_disposal_keeps_unvisited_states_for_shutdown(self):
        for operation in ("expire", "close_all"):
            with self.subTest(operation=operation):
                started = asyncio.Event()

                async def dispose(state):
                    if state["resource"] is first:
                        started.set()
                        await asyncio.Event().wait()
                    await _dispose(state)

                store = PendingStore(dispose=dispose, log=logging.getLogger("test"))
                first, second = FakeResource(), FakeResource()
                for pid, resource in (("first", first), ("second", second)):
                    await store.put(pid, {"created_at": time.time(), "resource": resource})
                for state in store._states.values():
                    state["created_at"] = 0
                task = asyncio.create_task(getattr(store, operation)())
                await asyncio.wait_for(started.wait(), timeout=1)
                task.cancel()
                with self.assertRaises(asyncio.CancelledError):
                    await task
                self.assertIn("second", store)
                self.assertEqual(second.closed, 0)
                await store.close_all()
                self.assertEqual(second.closed, 1)
                self.assertEqual(len(store), 0)


class AppOptionsTest(unittest.TestCase):
    def setUp(self):
        key = patch.dict(os.environ, {"APP_SIDECAR_API_KEY": "k"})
        key.start()
        self.addCleanup(key.stop)

    def test_without_a_validation_detail_fastapi_answers_422(self):
        with TestClient(_app()) as client:
            self.assertEqual(client.post("/initiate", json={}, headers={KEY_HEADER: "k"}).status_code, 422)

    def test_a_fixed_validation_detail_never_echoes_the_input(self):
        with TestClient(_app(validation_detail="INVALID_REQUEST")) as client:
            response = client.post("/initiate", json={"code": 1, "name": "secret"}, headers={KEY_HEADER: "k"})
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json(), {"detail": "INVALID_REQUEST"})

    def test_otp_field_detail_names_the_code_field(self):
        with TestClient(_app(validation_detail=otp_field_detail())) as client:
            bad_code = client.post("/initiate", json={"name": "n"}, headers={KEY_HEADER: "k"})
            bad_name = client.post("/initiate", json={"code": "1"}, headers={KEY_HEADER: "k"})
        self.assertEqual(bad_code.json(), {"detail": "INVALID_OTP"})
        self.assertEqual(bad_name.json(), {"detail": "INVALID_DATA"})

    def test_the_request_log_is_one_sanitised_line_and_skips_health(self):
        stream = io.StringIO()
        log = logging.getLogger("request-log-test")
        handler = logging.StreamHandler(stream)
        log.addHandler(handler)
        log.setLevel(logging.INFO)
        self.addCleanup(log.removeHandler, handler)
        with TestClient(_app(request_log=(log, "Bank"))) as client:
            client.get("/health")
            client.get("/x%0Aforged%20line", headers={KEY_HEADER: "k"})
            for headers in ({}, {KEY_HEADER: "wrong"}):
                response = client.get("/private-secret%0Aforged", headers=headers)
                self.assertEqual(response.status_code, 401)
        lines = stream.getvalue().splitlines()
        self.assertEqual(len(lines), 3)
        self.assertNotIn("private-secret", stream.getvalue())
        for line in lines[1:]:
            self.assertRegex(line, r"^Bank request refused \(duration=\d+\.\d\ds\)$")
        self.assertRegex(lines[0], r"^Bank request completed \(path=/x.?forged line; duration=\d+\.\d\ds\)$")

    def test_background_sweeps_while_up_and_closes_at_shutdown(self):
        events = []

        class Background:
            async def sweeper(self):
                events.append("sweep")
                await asyncio.Event().wait()

            async def close_all(self):
                events.append("close_all")

        with TestClient(_app(background=Background())):
            pass
        self.assertEqual(events, ["sweep", "close_all"])


if __name__ == "__main__":
    unittest.main()
