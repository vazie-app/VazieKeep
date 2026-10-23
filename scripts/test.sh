#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

run_area() {
  case "$1" in
    core) step "core tests"; (cd "$ROOT/core" && cargo test --workspace) ;;
    android) step "android unit tests"; (cd "$ROOT/apps/android" && ./gradlew --console=plain testDebugUnitTest) ;;
    apple)
      [[ "$(uname -s)" == "Darwin" ]] || { skip "apple needs macOS"; return; }
      step "apple tests"
      "$ROOT/scripts/build-core.sh" apple
      (cd "$ROOT/apps/apple/KeepAppleKit" && swift test)
      ;;
    desktop) step "desktop tests"; (cd "$ROOT/apps/desktop" && ./gradlew --console=plain test) ;;
    extension)
      step "browser extension tests (real WASM package)"
      "$ROOT/scripts/build-core.sh" wasm
      (cd "$ROOT/apps/browser-extension" && pnpm_cmd install --frozen-lockfile && pnpm_cmd run test)
      ;;
    *) echo "unknown area: $1" >&2; exit 1 ;;
  esac
}

while read -r area; do run_area "$area"; done < <(resolve_areas "$@")
