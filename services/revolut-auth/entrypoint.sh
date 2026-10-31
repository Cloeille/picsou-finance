#!/bin/sh
# Start a virtual X display, then run the API server under it.
#
# POST /sync launches Camoufox (Firefox) with headless=False when a re-auth is
# needed, so the process needs a DISPLAY. We start Xvfb directly instead of the
# `xvfb-run` wrapper: on this slim/trixie base xvfb-run hangs on its X-readiness
# handshake and never exec's the wrapped command, so the sidecar never comes up.
#
# The image pre-creates /tmp/.X11-unix as root-owned 1777 before switching to the
# non-root revoauth user. Keep mkdir as a harmless fallback for derived images.
set -e

mkdir -p /tmp/.X11-unix
Xvfb :99 -screen 0 1280x1024x24 -nolisten tcp &
export DISPLAY=:99

# Wait (briefly) for the X socket so a fast /sync right after boot still has a display.
i=0
while [ ! -S /tmp/.X11-unix/X99 ] && [ "$i" -lt 25 ]; do
    i=$((i + 1))
    sleep 0.2
done

exec uvicorn main:app --host 0.0.0.0 --port 8002 --timeout-keep-alive 65
