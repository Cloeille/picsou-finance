"""Login attempts parked between /initiate and /complete."""

import asyncio
import logging
import time
from typing import Any, Awaitable, Callable

from fastapi import HTTPException

State = dict[str, Any]


class PendingStore:
    """Pending second factors keyed by processId.

    Each state is a dict carrying `created_at` (epoch seconds); `dispose`
    releases what it pins (a browser, an httpx client) and must not raise.
    Taking a state past `ttl_seconds` disposes it instead of returning it.
    Parking sweeps expired entries before checking capacity; the background
    sweep also releases abandoned resources without another request.
    """

    def __init__(
        self,
        *,
        dispose: Callable[[State], Awaitable[None]],
        log: logging.Logger,
        ttl_seconds: float = 600,
        sweep_seconds: float = 30,
        max_size: int | None = None,
    ) -> None:
        self.ttl_seconds = ttl_seconds
        self.sweep_seconds = sweep_seconds
        self.max_size = max_size
        self._dispose = dispose
        self._log = log
        self._states: dict[str, State] = {}
        self._lock = asyncio.Lock()

    def __len__(self) -> int:
        return len(self._states)

    def __contains__(self, process_id: object) -> bool:
        return process_id in self._states

    async def put(self, process_id: str, state: State) -> bool:
        """Expire old states, then return False if `max_size` live attempts remain."""
        await self.expire()
        async with self._lock:
            if self.max_size is not None and len(self._states) >= self.max_size:
                return False
            self._states[process_id] = state
            return True

    async def put_or_raise(self, process_id: str, state: State, detail: str = "UPSTREAM_UNAVAILABLE") -> None:
        """Park a login or answer 503; the caller still owns refused resources."""
        if not await self.put(process_id, state):
            raise HTTPException(status_code=503, detail=detail)

    async def repark(self, process_id: str, state: State, detail: str = "UPSTREAM_UNAVAILABLE") -> None:
        """Take ownership of a retry, disposing it if parking fails or is cancelled."""
        parked = False
        try:
            await self.put_or_raise(process_id, state, detail)
            parked = True
        finally:
            if not parked:
                await self._dispose(state)

    async def take(self, process_id: str) -> State | None:
        """Transfer a live state to its caller, disposing an expired one once."""
        async with self._lock:
            state = self._states.pop(process_id, None)
        if state is not None and state["created_at"] < time.time() - self.ttl_seconds:
            await self._dispose(state)
            return None
        return state

    async def close(self, process_id: str) -> None:
        state = await self.take(process_id)
        if state is not None:
            await self._dispose(state)

    async def expire(self) -> None:
        cutoff = time.time() - self.ttl_seconds
        async with self._lock:
            expired = [pid for pid, state in self._states.items() if state["created_at"] < cutoff]
        # Leave unvisited states in the store if disposal fails or is cancelled;
        # shutdown (or the next sweep) must still be able to release them.
        for pid in expired:
            await self.close(pid)

    async def close_all(self) -> None:
        async with self._lock:
            process_ids = list(self._states)
        for pid in process_ids:
            await self.close(pid)

    async def sweeper(self) -> None:
        while True:
            await asyncio.sleep(self.sweep_seconds)
            try:
                await self.expire()
            except Exception as exc:
                # One bad sweep must not end the task: without it, expired states
                # keep their resources until shutdown and `max_size` eventually
                # rejects every new login.
                # Upstream exceptions may contain URLs or credentials.
                self._log.warning("Pending sweep failed error_type=%s", type(exc).__name__)
