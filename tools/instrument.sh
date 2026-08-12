#!/usr/bin/env bash
#
# Run WallPanel's instrumented (Espresso) tests against the network devices listed in
# tools/devices.json.
#
# The devices are reachable over TCP, so the container runs its own adb server and
# connects to them directly -- no host adb involved. adb connect and Gradle therefore
# have to share one container invocation, which is what the bash -c below is for.
#
# Usage:
#   ./tools/instrument.sh                 # all enabled devices, prod flavor
#   ./tools/instrument.sh -f qa           # different flavor
#   ./tools/instrument.sh -d kitchen      # single device by name from devices.json
#
# Gradle reports land in WallPanelPro/build/reports/androidTests/connected/.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
DEVICES_FILE="$SCRIPT_DIR/devices.json"

flavor="prod"
device=""

while getopts ":f:d:h" opt; do
    case "$opt" in
        f) flavor="$OPTARG" ;;
        d) device="$OPTARG" ;;
        h)
            sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        \?) echo "error: unknown option -$OPTARG" >&2; exit 64 ;;
        :)  echo "error: -$OPTARG needs a value" >&2; exit 64 ;;
    esac
done

case "$flavor" in
    prod|qa|dev) ;;
    *) echo "error: flavor must be prod, qa or dev (got '$flavor')" >&2; exit 64 ;;
esac

if [ ! -f "$DEVICES_FILE" ]; then
    echo "error: $DEVICES_FILE not found." >&2
    echo "       cp tools/devices.example.json tools/devices.json  # then add your devices" >&2
    exit 1
fi

# Flavor capitalised the way it appears in Gradle task names.
flavor_task="$(tr '[:lower:]' '[:upper:]' <<<"${flavor:0:1}")${flavor:1}"
gradle_task="connected${flavor_task}DebugAndroidTest"

# Pinning to one device is done with ANDROID_SERIAL, which AGP honours.
serial_export=""
if [ -n "$device" ]; then
    addr="$(jq -r --arg n "$device" \
        '.devices[] | select(.name == $n) | "\(.host):\(.port // 5555)"' "$DEVICES_FILE")"
    if [ -z "$addr" ]; then
        echo "error: no device named '$device' in $DEVICES_FILE" >&2
        echo "       known: $(jq -r '[.devices[].name] | join(", ")' "$DEVICES_FILE")" >&2
        exit 1
    fi
    serial_export="export ANDROID_SERIAL='$addr';"
    echo "Targeting $device ($addr)"
fi

echo "Task: $gradle_task"
echo

exec docker compose -f "$SCRIPT_DIR/docker-compose.yml" run --rm android bash -c "
    set -euo pipefail
    bash /project/tools/android/connect-devices.sh
    $serial_export
    echo
    ./gradlew $gradle_task --console=plain
"
