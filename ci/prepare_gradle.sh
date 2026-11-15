#!/bin/sh
set -eu

: "${CI_PROJECT_DIR:?CI_PROJECT_DIR is required}"
mkdir -p "$CI_PROJECT_DIR/.gradle"
WORK_ROOT="$(mktemp -d "$CI_PROJECT_DIR/.gradle/ci-gradle.XXXXXX")"
trap 'rm -rf "$WORK_ROOT"' EXIT

DIST="$WORK_ROOT/gradle-home/wrapper/dists/gradle-8.14.5-bin/690y85m0j9nfaub7xoiayko8a"
mkdir -p "$DIST"
ARCHIVE="$DIST/gradle-8.14.5-bin.zip"
curl --fail --silent --show-error --location --retry 3 \
    --proto '=https' --proto-redir '=https' \
    --output "$ARCHIVE" https://services.gradle.org/distributions/gradle-8.14.5-bin.zip
printf '%s  %s\n' \
    '6f74b601422d6d6fc4e1f9a1ab6522f642c2fdcbc15ae33ebd30ba3d7198e854' \
    "$ARCHIVE" | sha256sum -c - >&2
(
    cd "$DIST"
    jar xf "$ARCHIVE"
)
GRADLE="$DIST/gradle-8.14.5/bin/gradle"
[ -f "$GRADLE" ] || { echo "Verified Gradle distribution has no launcher" >&2; exit 1; }
chmod 700 "$GRADLE"
touch "$ARCHIVE.ok"
trap - EXIT
printf '%s\n' "$WORK_ROOT"
