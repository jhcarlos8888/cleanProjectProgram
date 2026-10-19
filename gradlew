#!/usr/bin/env sh
# Lightweight Gradle bootstrap launcher. Downloads the pinned official distribution
# and verifies it against the SHA-256 value published beside the ZIP.
set -eu

GRADLE_VERSION="8.14.5"
DIST_NAME="gradle-${GRADLE_VERSION}"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-${HOME:-$PWD}/.gradle}"
CACHE_ROOT="${GRADLE_USER_HOME}/cleanProjectProgram"
GRADLE_BIN="${CACHE_ROOT}/${DIST_NAME}/bin/gradle"
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

if [ ! -x "$GRADLE_BIN" ]; then
    for cmd in curl unzip sha256sum awk; do
        if ! command -v "$cmd" >/dev/null 2>&1; then
            echo "ERROR: falta '$cmd'. Instálalo y vuelve a ejecutar ./gradlew." >&2
            exit 1
        fi
    done
    mkdir -p "$CACHE_ROOT"
    TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/cleanProjectProgram-gradle.XXXXXX")
    trap 'rm -rf "$TMP_DIR"' EXIT HUP INT TERM
    ZIP_FILE="${TMP_DIR}/${DIST_NAME}-bin.zip"
    SHA_FILE="${ZIP_FILE}.sha256"
    URL="https://services.gradle.org/distributions/${DIST_NAME}-bin.zip"
    echo "Descargando Gradle ${GRADLE_VERSION} desde services.gradle.org..."
    curl --fail --location --silent --show-error "$URL" --output "$ZIP_FILE"
    curl --fail --location --silent --show-error "${URL}.sha256" --output "$SHA_FILE"
    EXPECTED=$(awk 'NR == 1 { print $1 }' "$SHA_FILE")
    ACTUAL=$(sha256sum "$ZIP_FILE" | awk '{ print $1 }')
    if [ -z "$EXPECTED" ] || [ "$EXPECTED" != "$ACTUAL" ]; then
        echo "ERROR: el SHA-256 de la distribución de Gradle no coincide. Se cancela la instalación." >&2
        exit 1
    fi
    unzip -q "$ZIP_FILE" -d "$TMP_DIR"
    rm -rf "${CACHE_ROOT}/${DIST_NAME}.new"
    mv "${TMP_DIR}/${DIST_NAME}" "${CACHE_ROOT}/${DIST_NAME}.new"
    rm -rf "$GRADLE_BIN" "${CACHE_ROOT}/${DIST_NAME}"
    mv "${CACHE_ROOT}/${DIST_NAME}.new" "${CACHE_ROOT}/${DIST_NAME}"
fi

cd "$APP_HOME"
exec "$GRADLE_BIN" "$@"
