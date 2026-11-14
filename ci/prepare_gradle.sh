#!/bin/sh
set -eu

: "${CI_PROJECT_DIR:?CI_PROJECT_DIR is required}"
mkdir -p "$CI_PROJECT_DIR/.gradle"
WORK_ROOT="$(mktemp -d "$CI_PROJECT_DIR/.gradle/ci-gradle.XXXXXX")"
trap 'rm -rf "$WORK_ROOT"' EXIT

DIST="$WORK_ROOT/gradle-home/wrapper/dists/gradle-8.10.2-bin/a04bxjujx95o3nb99gddekhwo"
mkdir -p "$DIST"
ARCHIVE="$DIST/gradle-8.10.2-bin.zip"
curl --fail --silent --show-error --location --retry 3 \
    --proto '=https' --proto-redir '=https' \
    --output "$ARCHIVE" https://services.gradle.org/distributions/gradle-8.10.2-bin.zip
printf '%s  %s\n' \
    '31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26' \
    "$ARCHIVE" | sha256sum -c - >&2
(
    cd "$DIST"
    jar xf "$ARCHIVE"
)
GRADLE="$DIST/gradle-8.10.2/bin/gradle"
[ -f "$GRADLE" ] || { echo "Verified Gradle distribution has no launcher" >&2; exit 1; }
chmod 700 "$GRADLE"
touch "$ARCHIVE.ok"
trap - EXIT
printf '%s\n' "$WORK_ROOT"
