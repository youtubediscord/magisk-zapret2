#!/usr/bin/env bash
set -euo pipefail

DEFAULT_ROOT="$(cd -- "$(dirname "$0")/../.." && pwd)"
ROOT_ARG="${1:-$DEFAULT_ROOT}"
[[ -d "$ROOT_ARG" && ! -L "$ROOT_ARG" ]] || {
    printf 'release-channel test: invalid repository root\n' >&2
    exit 1
}
ROOT="$(cd -- "$ROOT_ARG" && pwd -P)"
WORKFLOW="$ROOT/.github/workflows/build.yml"
BUILDER="$ROOT/.codex/skills/build-zapret2-locally/scripts/build-local-release.sh"
PUBLISHER="$ROOT/.codex/skills/build-zapret2-locally/scripts/publish-stable-release.sh"

fail() {
    printf 'release-channel test: %s\n' "$*" >&2
    exit 1
}

for required_file in "$WORKFLOW" "$BUILDER" "$PUBLISHER"; do
    [[ -f "$required_file" && ! -L "$required_file" && -s "$required_file" ]] ||
        fail "required file is missing or unsafe: $required_file"
done

bash -n "$BUILDER"
bash -n "$PUBLISHER"
bash -n "$ROOT/tests/release/source-policy.sh"

if grep -Fq 'gh release create' "$WORKFLOW"; then
    fail "background Actions workflow still publishes GitHub Releases"
fi
if grep -Fq 'contents: write' "$WORKFLOW"; then
    fail "background Actions workflow still has release write permission"
fi
grep -Fq 'name: Background release validation' "$WORKFLOW" ||
    fail "Actions workflow is not explicitly identified as background validation"
grep -Fq 'update_json=' "$BUILDER" ||
    fail "local builder does not produce stable update metadata"
grep -Fq 'build_info=' "$BUILDER" ||
    fail "local builder does not produce dev build metadata"
grep -Fq -- '--channel stable|dev is required' "$BUILDER" ||
    fail "local builder does not require an explicit stable or dev channel"
grep -Fq 'PRERELEASE_ID="dev.' "$BUILDER" ||
    fail "local dev builder does not assign a dev prerelease identity"
grep -Fq 'publishable: false' "$BUILDER" ||
    fail "local dev metadata is not explicitly non-publishable"
grep -Fq 'shell_tests=passed' "$BUILDER" ||
    fail "local builder does not qualify shell integration tests"
grep -Fq "gh release create \"\$VERSION_TAG\"" "$PUBLISHER" ||
    fail "local stable publisher does not create the canonical SemVer release"
grep -Fq -- '--latest' "$PUBLISHER" ||
    fail "local stable publisher does not select the release as Latest"
if grep -Fq -- '--prerelease' "$PUBLISHER"; then
    fail "local stable publisher marks the release as a prerelease"
fi
grep -Fq "\"\$UPDATE_PATH\"" "$PUBLISHER" ||
    fail "local stable publisher does not upload update.json"
if grep -Fq 'gh release create' "$BUILDER"; then
    fail "local builder can publish instead of remaining build-only"
fi

printf 'release-channel contract: passed\n'
