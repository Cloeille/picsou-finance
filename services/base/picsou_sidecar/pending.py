"""Login attempts parked between /initiate and /complete."""

import asyncio
import logging
import time
from typing import Any, Awaitable, Callable

State = dict[str, Any]


class PendingStore:
    """Pending second factors keyed by processId.

    Each state is a dict carrying `created_at` (epoch seconds); `dispose`
    releases what it pins (a browser, an httpx client) and must not raise.
    A state that outlives `ttl_seconds` is swept and disposed, so an abandoned
    login cannot hold its resources until shutdown.
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
        """False, and nothing stored, when `max_size` attempts are already pending."""
        async with self._lock:
            if self.max_size is not None and len(self._states) >= self.max_size:
                return False
            self._states[process_id] = state
            return True

    async def take(self, process_id: str) -> State | None:
        async with self._lock:
            return self._states.pop(process_id, None)

    async def close(self, process_id: str) -> None:
        state = await self.take(process_id)
        if state is not None:
            await self._dispose(state)

    async def expire(self) -> None:
        cutoff = time.time() - self.ttl_seconds
        async with self._lock:
            expired = [pid for pid, state in self._states.items() if state["created_at"] < cutoff]
            states = [self._states.pop(pid) for pid in expired]
        for state in states:
            await self._dispose(state)

    async def close_all(self) -> None:
        async with self._lock:
            states = list(self._states.values())
            self._states.clear()
        for state in states:
            await self._dispose(state)

    async def sweeper(self) -> None:
        while True:
            await asyncio.sleep(self.sweep_seconds)
            try:
                await self.expire()
            except Exception:
                # One bad sweep must not end the task: without it, expired states
                # keep their resources until shutdown and `max_size` eventually
                # rejects every new login.
                self._log.warning("Pending sweep failed", exc_info=True)
