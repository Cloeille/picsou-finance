#!/bin/sh
# Start a virtual X display, then run the API server under it.
#
# initiate() launches Camoufox (Firefox) with headless=False (see main.py) because
# AMEX's Akamai Bot Manager blocks the login POST from a headless fingerprint, so the
# process needs a DISPLAY. We start Xvfb directly instead of the `xvfb-run` wrapper: on
# this slim/bookworm base xvfb-run hangs on its X-readiness handshake and never exec's
# the wrapped command, so the sidecar never comes up.
#
# The image pre-creates /tmp/.X11-unix as root-owned 1777 before switching to the
# non-root amex user. Keep mkdir as a harmless fallback for derived images.
set -e

mkdir -p /tmp/.X11-unix
# On `podman start` of an existing container the lock/socket from the previous
# run survive in /tmp (tmpfs is per-container but persists across stop/start),
# so Xvfb refuses to bind :98 and exits; clear them before relaunching.
rm -f /tmp/.X98-lock /tmp/.X11-unix/X98
Xvfb :98 -screen 0 1366x768x24 -nolisten tcp &
export DISPLAY=:98

# Wait (briefly) for the X socket so a fast /initiate right after boot still has a display.
i=0
while [ ! -S /tmp/.X11-unix/X98 ] && [ "$i" -lt 25 ]; do
    i=$((i + 1))
    sleep 0.2
done

exec uvicorn main:app --host 0.0.0.0 --port 8001 --timeout-keep-alive 65
