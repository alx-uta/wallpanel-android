#!/usr/bin/env bash
#
# Run containerised dev tools for WallPanel.
#
# Wrapper around docker compose so the project can be built, linted and tested without
# installing a JDK, the Android SDK or Node on the host. Services are defined in
# tools/docker-compose.yml.
#
# Usage:
#   ./tools/run.sh android ./gradlew assembleProdDebug
#   ./tools/run.sh android ./gradlew testProdDebugUnitTest
#   ./tools/run.sh android ./gradlew lintProdDebug
#   ./tools/run.sh node    npm run build
#
# Invoke the Gradle wrapper as ./gradlew, never "sh gradlew": the script is
# bash-specific and /bin/sh in the container is dash.
#
# For instrumented tests against the network devices, use tools/instrument.sh -- it
# connects adb before handing off to Gradle.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"

if [ "$#" -lt 1 ]; then
    echo "usage: $(basename "$0") <android|node> <command...>" >&2
    exit 64
fi

service="$1"
shift

case "$service" in
    android|node) ;;
    *)
        echo "error: unknown service '$service' (expected 'android' or 'node')" >&2
        exit 64
        ;;
esac

if [ ! -f "$COMPOSE_FILE" ]; then
    echo "error: docker-compose.yml not found at $COMPOSE_FILE" >&2
    exit 1
fi

exec docker compose -f "$COMPOSE_FILE" run --rm "$service" "$@"
