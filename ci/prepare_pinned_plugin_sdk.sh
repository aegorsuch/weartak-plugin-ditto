#!/bin/sh
set -eu

PROJECT_ROOT="${CI_PROJECT_DIR:-$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)}"
CORE_BUILDER_COMMIT="00290203f9a6bb09870c53f2cdac0e110ed88125"
CORE_REPOSITORY="https://git.tak.gov/core/weartak-core/wearos-tak-civ.git"

: "${CI_JOB_TOKEN:?GitLab CI_JOB_TOKEN is required to fetch pinned core SDK build inputs}"
mkdir -p "$PROJECT_ROOT/.gradle"
SDK_AAR="$PROJECT_ROOT/.gradle/verified-weartak-plugin-sdk-release.aar"
rm -f "$SDK_AAR"
WORK_ROOT="$(mktemp -d "$PROJECT_ROOT/.gradle/ci-plugin-sdk.XXXXXX")"
trap 'rm -rf "$WORK_ROOT"' EXIT

ASKPASS="$WORK_ROOT/askpass.sh"
cat > "$ASKPASS" <<'EOF'
#!/bin/sh
case "$1" in
    *Username*) printf '%s\n' 'gitlab-ci-token' ;;
    *Password*) printf '%s\n' "$CI_JOB_TOKEN" ;;
    *) exit 1 ;;
esac
EOF
chmod 700 "$ASKPASS"

CORE_ROOT="$WORK_ROOT/core"
mkdir -p "$CORE_ROOT"
git -C "$CORE_ROOT" init --quiet
git -C "$CORE_ROOT" remote add origin "$CORE_REPOSITORY"
GIT_ASKPASS="$ASKPASS" GIT_TERMINAL_PROMPT=0 \
    git -C "$CORE_ROOT" fetch --quiet --depth=1 --filter=blob:none origin "$CORE_BUILDER_COMMIT"
unset CI_JOB_TOKEN

INPUTS="
ci/build_plugin_sdk.sh
ci/plugin-sdk.json
ci/plugin-sdk-transport/.gitattributes
ci/plugin-sdk-transport/overlay-source.sha256
ci/plugin-sdk-transport/transport-v1-overlay.zip
"
for path in $INPUTS; do
    git -C "$CORE_ROOT" cat-file -e "$CORE_BUILDER_COMMIT:$path"
done
git -C "$CORE_ROOT" -c core.autocrlf=false checkout "$CORE_BUILDER_COMMIT" -- $INPUTS

(
    cd "$CORE_ROOT"
    sh ci/build_plugin_sdk.sh
) >&2

PIN="$CORE_ROOT/ci/plugin-sdk.json"
SDK_COMMIT="$(jq -er .commit "$PIN")"
OVERLAY_SHA="$(jq -er .overlay.source_sha256 "$PIN")"
SDK_ID="$(printf '%s' "$SDK_COMMIT" | cut -c1-12)-$(printf '%s' "$OVERLAY_SHA" | cut -c1-12)"
SOURCE_AAR="$CORE_ROOT/.gradle/sdk/$SDK_ID/build/outputs/aar/weartak-plugin-sdk-release.aar"
EXPECTED_SHA="$(jq -er .release_sha256.linux "$PIN")"

[ -f "$SOURCE_AAR" ] || { echo "Verified plugin SDK AAR was not produced" >&2; exit 1; }
ACTUAL_SHA="$(sha256sum "$SOURCE_AAR" | awk '{print $1}')"
[ "$ACTUAL_SHA" = "$EXPECTED_SHA" ] || { echo "Unexpected plugin SDK AAR hash" >&2; exit 1; }
cp "$SOURCE_AAR" "$SDK_AAR"
printf '%s\n' "$SDK_AAR"
