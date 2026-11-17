#!/bin/sh
set -eu

: "${CI_PROJECT_DIR:?CI_PROJECT_DIR is required}"
mkdir -p "$CI_PROJECT_DIR/.gradle"
OUTPUT="$CI_PROJECT_DIR/.gradle/wear-version.properties"
rm -f "$OUTPUT"
: "${CI_JOB_TOKEN:?CI_JOB_TOKEN is required to authenticate canonical core main}"
WORK_ROOT="$(mktemp -d "$CI_PROJECT_DIR/.gradle/ci-version.XXXXXX")"
trap 'rm -rf "$WORK_ROOT"' EXIT

cat > "$WORK_ROOT/askpass.sh" <<'EOF'
#!/bin/sh
case "$1" in
    *Username*) printf '%s\n' 'gitlab-ci-token' ;;
    *Password*) printf '%s\n' "$CI_JOB_TOKEN" ;;
    *) exit 1 ;;
esac
EOF
chmod 700 "$WORK_ROOT/askpass.sh"
git -C "$WORK_ROOT" init --quiet
git -C "$WORK_ROOT" remote add origin https://git.tak.gov/core/weartak-core/wearos-tak-civ.git
(
    export GIT_ASKPASS="$WORK_ROOT/askpass.sh" GIT_TERMINAL_PROMPT=0
    git -C "$WORK_ROOT" -c credential.helper= fetch --quiet --depth=1 origin refs/heads/main
)
SOURCE_SHA="$(git -C "$WORK_ROOT" rev-parse FETCH_HEAD)"
git -C "$WORK_ROOT" show "$SOURCE_SHA:wear-version.properties" > "$WORK_ROOT/canonical.properties"
unset CI_JOB_TOKEN
java "$CI_PROJECT_DIR/buildSrc/src/main/java/com/weartak/version/WearVersion.java" \
    "$WORK_ROOT/canonical.properties" "$SOURCE_SHA" "$OUTPUT"
