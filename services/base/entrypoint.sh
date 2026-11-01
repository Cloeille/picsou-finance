#!/bin/sh
# Headful Camoufox needs Xvfb. Launch it directly: xvfb-run can hang on slim images.
set -e

XVFB_DISPLAY=${XVFB_DISPLAY:-:99}
XVFB_SCREEN=${XVFB_SCREEN:-1280x1024x24}
SIDECAR_PORT=${SIDECAR_PORT:-8001}
display_number=${XVFB_DISPLAY#:}
socket=/tmp/.X11-unix/X${display_number}

mkdir -p /tmp/.X11-unix
# Container stop/start can leave the previous display lock and socket in /tmp.
rm -f "/tmp/.X${display_number}-lock" "$socket"
Xvfb "$XVFB_DISPLAY" -screen 0 "$XVFB_SCREEN" -nolisten tcp &
xvfb_pid=$!
export DISPLAY="$XVFB_DISPLAY"

i=0
while [ ! -S "$socket" ] && [ "$i" -lt 25 ]; do
    if ! kill -0 "$xvfb_pid" 2>/dev/null; then
        echo "Xvfb exited before the display became ready" >&2
        exit 1
    fi
    i=$((i + 1))
    sleep 0.2
done
if [ ! -S "$socket" ]; then
    echo "Xvfb failed to become ready" >&2
    kill "$xvfb_pid" 2>/dev/null || true
    exit 1
fi

# An explicit command allows offline browser smoke tests under the same display.
if [ "$#" -gt 0 ]; then
    exec "$@"
fi
exec uvicorn main:app --host 0.0.0.0 --port "$SIDECAR_PORT" --timeout-keep-alive 65
