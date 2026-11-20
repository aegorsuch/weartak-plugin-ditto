#!/bin/sh
set -eu

PROJECT_ROOT="${CI_PROJECT_DIR:-$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)}"
CORE_BUILDER_COMMIT="00290203f9a6bb09870c53f2cdac0e110ed88125"
HOST_SERVICES_SDK_COMMIT="cbe74590547bab276e6afa0ae3904cc37fcf1507"
CORE_REPOSITORY="https://git.tak.gov/core/weartak-core/wearos-tak-civ.git"

if [ "${CI:-}" = "true" ] && [ -z "${CI_JOB_TOKEN:-}" ]; then
    echo "GitLab CI_JOB_TOKEN is required in CI" >&2
    exit 1
fi
mkdir -p "$PROJECT_ROOT/.gradle"
SDK_AAR="$PROJECT_ROOT/.gradle/verified-weartak-plugin-sdk-release.aar"
rm -f "$SDK_AAR"
HOST_SDK_AAR="$PROJECT_ROOT/.gradle/verified-weartak-plugin-host-services-sdk-release.aar"
rm -f "$HOST_SDK_AAR"
WORK_ROOT="$(mktemp -d "$PROJECT_ROOT/.gradle/ci-plugin-sdk.XXXXXX")"
trap 'rm -rf "$WORK_ROOT"' EXIT

ASKPASS="$WORK_ROOT/askpass.sh"
if [ -n "${CI_JOB_TOKEN:-}" ]; then
    cat > "$ASKPASS" <<'EOF'
#!/bin/sh
case "$1" in
    *Username*) printf '%s\n' 'gitlab-ci-token' ;;
    *Password*) printf '%s\n' "$CI_JOB_TOKEN" ;;
    *) exit 1 ;;
esac
EOF
    chmod 700 "$ASKPASS"
fi

CORE_ROOT="$WORK_ROOT/core"
mkdir -p "$CORE_ROOT"
git -C "$CORE_ROOT" init --quiet
git -C "$CORE_ROOT" remote add origin "$CORE_REPOSITORY"

INPUTS="
ci/build_plugin_sdk.sh
ci/plugin-sdk.json
ci/plugin-sdk-transport/.gitattributes
ci/plugin-sdk-transport/overlay-source.sha256
ci/plugin-sdk-transport/transport-v1-overlay.zip
"
(
    export GIT_TERMINAL_PROMPT=0
    if [ -n "${CI_JOB_TOKEN:-}" ]; then
        export GIT_ASKPASS="$ASKPASS"
    else
        unset GIT_ASKPASS
        export GCM_INTERACTIVE=never
    fi
    echo "Fetching pinned core SDK build inputs" >&2
    git -C "$CORE_ROOT" fetch --quiet --depth=1 --filter=blob:none origin "$CORE_BUILDER_COMMIT"
    echo "Materializing pinned core SDK build inputs" >&2
    for path in $INPUTS; do
        git -C "$CORE_ROOT" cat-file -e "$CORE_BUILDER_COMMIT:$path"
    done
    git -C "$CORE_ROOT" -c core.autocrlf=false checkout "$CORE_BUILDER_COMMIT" -- $INPUTS
    echo "Fetching pinned host-services SDK source" >&2
    git -C "$CORE_ROOT" fetch --quiet --depth=1 --filter=blob:none origin "$HOST_SERVICES_SDK_COMMIT"
    HOST_INPUTS="
gradle/libs.versions.toml
weartak-plugin-host-services-sdk
"
    for path in $HOST_INPUTS; do
        git -C "$CORE_ROOT" cat-file -e "$HOST_SERVICES_SDK_COMMIT:$path"
    done
    git -C "$CORE_ROOT" -c core.autocrlf=false checkout "$HOST_SERVICES_SDK_COMMIT" -- $HOST_INPUTS
)
unset CI_JOB_TOKEN

case "$(uname -s)" in
    Linux*)
        SDK_PLATFORM="linux"
        (cd "$CORE_ROOT" && sh ci/build_plugin_sdk.sh) >&2
        ;;
    MINGW*|MSYS*|CYGWIN*)
        SDK_PLATFORM="windows"
        (cd "$CORE_ROOT" && python ci/build_plugin_sdk.py) >&2
        ;;
    *)
        echo "Unsupported plugin SDK build platform" >&2
        exit 1
        ;;
esac

HOST_SDK_ROOT="$CORE_ROOT/weartak-plugin-host-services-sdk"
GRADLE="$GRADLE_USER_HOME/wrapper/dists/gradle-8.14.5-bin/690y85m0j9nfaub7xoiayko8a/gradle-8.14.5/bin/gradle"
[ -x "$GRADLE" ] || { echo "Verified Gradle 8.14.5 launcher is unavailable" >&2; exit 1; }
"$GRADLE" -p "$HOST_SDK_ROOT" assembleRelease --no-daemon --max-workers=2 \
    -Pkotlin.compiler.execution.strategy=in-process --console=plain >&2

PIN="$CORE_ROOT/ci/plugin-sdk.json"
SDK_COMMIT="$(jq -er .commit "$PIN")"
OVERLAY_SHA="$(jq -er .overlay.source_sha256 "$PIN")"
SDK_ID="$(printf '%s' "$SDK_COMMIT" | cut -c1-12)-$(printf '%s' "$OVERLAY_SHA" | cut -c1-12)"
SOURCE_AAR="$CORE_ROOT/.gradle/sdk/$SDK_ID/build/outputs/aar/weartak-plugin-sdk-release.aar"
EXPECTED_SHA="$(jq -er ".release_sha256.$SDK_PLATFORM" "$PIN")"

[ -f "$SOURCE_AAR" ] || { echo "Verified plugin SDK AAR was not produced" >&2; exit 1; }
ACTUAL_SHA="$(sha256sum "$SOURCE_AAR" | awk '{print $1}')"
[ "$ACTUAL_SHA" = "$EXPECTED_SHA" ] || { echo "Unexpected plugin SDK AAR hash" >&2; exit 1; }
cp "$SOURCE_AAR" "$SDK_AAR"
HOST_SOURCE_AAR="$HOST_SDK_ROOT/build/outputs/aar/weartak-plugin-host-services-sdk-release.aar"
[ -f "$HOST_SOURCE_AAR" ] || { echo "Pinned host-services SDK AAR was not produced" >&2; exit 1; }
HOST_CLASSES_DIR="$WORK_ROOT/host-sdk-classes"
mkdir -p "$HOST_CLASSES_DIR"
(cd "$HOST_CLASSES_DIR" && jar xf "$HOST_SOURCE_AAR" classes.jar)
HOST_CLASSES_SHA="$(sha256sum "$HOST_CLASSES_DIR/classes.jar" | awk '{print $1}')"
[ "$HOST_CLASSES_SHA" = "7aa37dfec4731f343e3d727385608b1645f8ff15541af3eef69cc8bf2a2ba1a3" ] || {
    echo "Unexpected host-services SDK contract classes" >&2; exit 1;
}
HOST_SOURCE_SHA="$(sha256sum "$HOST_SOURCE_AAR" | awk '{print $1}')"
cp "$HOST_SOURCE_AAR" "$HOST_SDK_AAR"
HOST_COPIED_SHA="$(sha256sum "$HOST_SDK_AAR" | awk '{print $1}')"
[ "$HOST_COPIED_SHA" = "$HOST_SOURCE_SHA" ] || { echo "Host-services SDK AAR copy checksum mismatch" >&2; exit 1; }
printf '%s\n' "$SDK_AAR"
