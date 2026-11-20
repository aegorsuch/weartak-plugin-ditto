#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
WORK_ROOT="$(mktemp -d)"
trap 'rm -rf "$WORK_ROOT"' EXIT
javac -d "$WORK_ROOT" "$ROOT/buildSrc/src/main/java/com/weartak/version/WearVersion.java" \
    "$ROOT/ci/WearVersionTest.java"
java -cp "$WORK_ROOT" WearVersionTest

# Exercise the fetch boundary without contacting TAK.gov or using real credentials.
FIXTURE="$WORK_ROOT/project"
mkdir -p "$WORK_ROOT/bin" "$FIXTURE/buildSrc/src/main/java/com/weartak/version" "$FIXTURE/.gradle"
cp "$ROOT/buildSrc/src/main/java/com/weartak/version/WearVersion.java" \
    "$FIXTURE/buildSrc/src/main/java/com/weartak/version/"
cat > "$WORK_ROOT/bin/git" <<'EOF'
#!/bin/sh
set -eu
[ "$1" = "-C" ]
shift 2
case "$1" in
    init) exit 0 ;;
    remote)
        [ "$*" = "remote add origin https://git.tak.gov/core/weartak-core/wearos-tak-civ.git" ]
        ;;
    -c)
        [ "$*" = "-c credential.helper= fetch --quiet --depth=1 origin refs/heads/main" ]
        [ -n "${GIT_ASKPASS:-}" ] && [ "$GIT_TERMINAL_PROMPT" = "0" ]
        [ "${FAIL_FETCH:-false}" = "false" ]
        ;;
    rev-parse) printf '%s\n' '1111111111111111111111111111111111111111' ;;
    show)
        [ "$2" = "1111111111111111111111111111111111111111:wear-version.properties" ]
        printf 'versionName=5.8.4\nbaseVersionCode=584\n'
        ;;
    *) exit 1 ;;
esac
EOF
chmod 700 "$WORK_ROOT/bin/git"
(
    export PATH="$WORK_ROOT/bin:$PATH" CI_PROJECT_DIR="$FIXTURE" CI=true CI_JOB_TOKEN=test-only
    export CI_PIPELINE_SOURCE=pipeline CI_PIPELINE_IID=42
    export CI_COMMIT_SHA=abcdef0122222222222222222222222222222222
    export WEARTAK_VERSION_SOURCE_PROJECT_ID=6739
    export WEARTAK_VERSION_SOURCE_SHA=1111111111111111111111111111111111111111
    export WEARTAK_VERSION_NAME=5.8.4 WEARTAK_BASE_VERSION_CODE=584
    sh "$ROOT/ci/prepare_wear_version.sh"
    [ -s "$FIXTURE/.gradle/wear-version.properties" ]
    export FAIL_FETCH=true
    if sh "$ROOT/ci/prepare_wear_version.sh"; then
        echo "Expected failed source authentication to reject stale version input" >&2
        exit 1
    fi
    [ ! -e "$FIXTURE/.gradle/wear-version.properties" ]
    export FAIL_FETCH=false WEARTAK_VERSION_NAME=99.0.0
    if sh "$ROOT/ci/prepare_wear_version.sh" > "$WORK_ROOT/rejection.log" 2>&1; then
        echo "Expected spoofed forwarded version to fail" >&2
        exit 1
    fi
    [ ! -e "$FIXTURE/.gradle/wear-version.properties" ]
)
printf '%s\n' 'Canonical source boundary tests passed'
