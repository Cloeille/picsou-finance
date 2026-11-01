"""The FastAPI app every sidecar starts from.

It owns the shared-key boundary, /health, the validation envelope and the
request log. A sidecar adds its routes and parsers on top.
"""

import asyncio
import logging
import os
import secrets
import time
from contextlib import asynccontextmanager
from typing import Callable, Protocol

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .logs import safe

KEY_ENV = "APP_SIDECAR_API_KEY"
KEY_HEADER = "X-Picsou-Sidecar-Key"
# The backend's SidecarWebClientFactory matches a 401 on this challenge, which is
# how it tells a key mismatch from a bank rejecting the credentials. See
# docs/decisions/2026-09-17-sidecar-shared-secret-channel.md.
CHALLENGE = "Picsou-Sidecar-Key"
HEALTH_PATH = "/health"

ValidationDetail = str | Callable[[Request, RequestValidationError], str]


class Background(Protocol):
    """Work that runs for the app's lifetime: a PendingStore, or a module with the same two hooks."""

    async def sweeper(self) -> None: ...

    async def close_all(self) -> None: ...


def sidecar_key() -> str:
    # Trimmed like the backend trims it before sending.
    return os.environ.get(KEY_ENV, "").strip()


def _key_matches(supplied: str) -> bool:
    expected = sidecar_key()
    # Starlette exposes wire header bytes through Latin-1; recover those bytes so
    # a UTF-8 key with non-ASCII characters compares exactly as configured.
    return bool(expected) and secrets.compare_digest(
        supplied.encode("latin-1"), expected.encode("utf-8")
    )


def otp_field_detail(otp_detail: str = "INVALID_OTP", other: str = "INVALID_DATA") -> ValidationDetail:
    """`otp_detail` when the invalid field is the second-factor `code`, else `other`."""

    def detail(_: Request, exc: RequestValidationError) -> str:
        fields = {str(error["loc"][-1]) for error in exc.errors() if error.get("loc")}
        return otp_detail if "code" in fields else other

    return detail


def create_app(
    *,
    request_log: tuple[logging.Logger, str] | None = None,
    background: Background | None = None,
    validation_detail: ValidationDetail | None = None,
    **fastapi_kwargs,
) -> FastAPI:
    """A FastAPI app behind the shared sidecar key.

    request_log: (logger, label) to log duration and authenticated request paths.
    background: its sweeper runs while the app is up; close_all runs at shutdown.
    validation_detail: answer an invalid body with 400 and this detail, never the
        offending input. None keeps FastAPI's own 422.
    """

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        if not sidecar_key():
            raise RuntimeError(f"{KEY_ENV} must be configured and non-blank")
        if background is None:
            yield
            return
        sweeper = asyncio.create_task(background.sweeper())
        try:
            yield
        finally:
            sweeper.cancel()
            try:
                await sweeper
            except asyncio.CancelledError:
                pass
            await background.close_all()

    app = FastAPI(lifespan=lifespan, **fastapi_kwargs)

    # Before routing, body parsing or any upstream work: /docs, /openapi.json and
    # unknown paths are refused too. The header value is never logged.
    @app.middleware("http")
    async def authenticate_sidecar_request(request: Request, call_next):
        request.state.sidecar_authenticated = _key_matches(request.headers.get(KEY_HEADER, ""))
        if request.url.path != HEALTH_PATH and not request.state.sidecar_authenticated:
            return JSONResponse(
                status_code=401,
                content={"detail": "UNAUTHORIZED"},
                headers={"WWW-Authenticate": CHALLENGE},
            )
        return await call_next(request)

    if request_log is not None:
        log, label = request_log

        # Added after the key check, so it wraps it and logs refused calls too.
        @app.middleware("http")
        async def log_request_duration(request: Request, call_next):
            started_at = time.monotonic()
            try:
                return await call_next(request)
            finally:
                if request.url.path != HEALTH_PATH and not getattr(request.state, "sidecar_authenticated", False):
                    log.info("%s request refused (duration=%.2fs)", label, time.monotonic() - started_at)
                elif request.url.path != HEALTH_PATH:
                    # Uvicorn percent-decodes the path, so a caller can plant
                    # CR/LF in it and forge log lines.
                    log.info(
                        "%s request completed (path=%s; duration=%.2fs)",
                        label,
                        safe(request.url.path, 200),
                        time.monotonic() - started_at,
                    )

    if validation_detail is not None:

        @app.exception_handler(RequestValidationError)
        async def validation_exception_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
            detail = validation_detail if isinstance(validation_detail, str) else validation_detail(request, exc)
            return JSONResponse(status_code=400, content={"detail": detail})

    @app.get(HEALTH_PATH)
    async def health() -> dict:
        # The admin instance-status check reads `base` to spot a sidecar still
        # on a pre-base image.
        base = os.environ.get("PICSOU_SIDECAR_BASE")
        return {"status": "ok", "base": base} if base else {"status": "ok"}

    return app
