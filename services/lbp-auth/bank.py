"""The bank-specific layer of the La Banque Postale sidecar.

Nothing here talks to the real bank yet: no live session has been captured, so
the login page, the anti-bot posture and the account payloads are unknown. The
HTTP contract in `main.py` is final; only this module changes when the recon is
done.

To implement it, provide an object with the five async methods below and bind it
to `main.bank_client` (or replace `UnverifiedBankClient`). The methods must
return the normalized shapes documented in the README and must raise the typed
errors `main` maps to the backend's codes. Never log a password, a keypad
position, a cookie or a raw bank payload.
"""

from typing import Any, Protocol


class BankFlowUnverified(Exception):
    """Raised until the bank's web client has been observed and implemented."""


class BankClient(Protocol):
    async def initiate(self, customer_id: str) -> dict[str, Any]:
        """Start a login and return {processId, keypad: {images, columns}, expiresInSeconds}."""

    async def keypad(self, process_id: str, positions: list[int]) -> None:
        """Submit the clicked key positions and leave the attempt waiting for the mobile approval."""

    async def complete(self, process_id: str) -> str:
        """Wait for the mobile approval (up to 150 s) and return the opaque session state."""

    async def check(self, session_state: str) -> int:
        """Replay the session and return how many seconds it stays usable."""

    async def accounts(self, session_state: str) -> dict[str, Any]:
        """Return the normalized {accounts, unsupported} snapshot."""


class UnverifiedBankClient:
    """Refuses every call until the recon is done, with one honest typed error."""

    async def initiate(self, customer_id: str) -> dict[str, Any]:
        raise BankFlowUnverified()

    async def keypad(self, process_id: str, positions: list[int]) -> None:
        raise BankFlowUnverified()

    async def complete(self, process_id: str) -> str:
        raise BankFlowUnverified()

    async def check(self, session_state: str) -> int:
        raise BankFlowUnverified()

    async def accounts(self, session_state: str) -> dict[str, Any]:
        raise BankFlowUnverified()
