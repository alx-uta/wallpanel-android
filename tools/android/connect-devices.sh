#!/usr/bin/env bash
#
# Connects the container's adb server to the network devices listed in
# tools/devices.json, then waits for them to come online.
#
# Runs INSIDE the android container. The adb server is per-container, so this has to
# execute in the same `docker compose run` invocation as the Gradle command that needs
# the devices -- see tools/instrument.sh.
#
# The container's adb identity (/root/.android/adbkey) lives in the android-config
# volume, so a device only has to authorise "always allow from this computer" once.

set -euo pipefail

DEVICES_FILE="${DEVICES_FILE:-/project/tools/devices.json}"

if [ ! -f "$DEVICES_FILE" ]; then
    echo "error: $DEVICES_FILE not found." >&2
    echo "       Copy tools/devices.example.json to tools/devices.json and add your devices." >&2
    exit 1
fi

if ! jq empty "$DEVICES_FILE" 2>/dev/null; then
    echo "error: $DEVICES_FILE is not valid JSON (trailing comma?)." >&2
    jq empty "$DEVICES_FILE" || true
    exit 1
fi

mapfile -t targets < <(
    jq -r '.devices[] | select(.enabled == true) | "\(.name)\t\(.host):\(.port // 5555)"' "$DEVICES_FILE"
)

if [ "${#targets[@]}" -eq 0 ]; then
    echo "error: no enabled devices in $DEVICES_FILE." >&2
    exit 1
fi

adb start-server >/dev/null 2>&1 || true

connected=0
for row in "${targets[@]}"; do
    name="${row%%$'\t'*}"
    addr="${row##*$'\t'}"

    printf 'connecting %-12s %s ... ' "$name" "$addr"
    # adb connect exits 0 even when it fails, so the message has to be inspected.
    out="$(adb connect "$addr" 2>&1 || true)"
    case "$out" in
        *"connected to"*)
            echo "ok"
            connected=$((connected + 1))
            ;;
        *"failed to authenticate"*)
            echo "NEEDS AUTHORISATION"
            echo "    Unlock $name and accept the USB-debugging prompt, then re-run." >&2
            ;;
        *)
            echo "FAILED"
            echo "    $out" >&2
            ;;
    esac
done

if [ "$connected" -eq 0 ]; then
    echo "error: no devices connected." >&2
    exit 1
fi

echo
echo "waiting for devices to report ready..."
deadline=$(( $(date +%s) + 60 ))
while :; do
    ready="$(adb devices | tail -n +2 | grep -cw 'device' || true)"
    [ "$ready" -ge "$connected" ] && break
    if [ "$(date +%s)" -ge "$deadline" ]; then
        echo "warning: only $ready of $connected devices reported ready after 60s." >&2
        break
    fi
    sleep 2
done

echo
adb devices
