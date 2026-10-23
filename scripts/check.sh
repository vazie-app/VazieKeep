#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

run_area() {
  case "$1" in
    core)
      step "core: fmt, clippy, test"
      (cd "$ROOT/core" &&
        cargo fmt --all --check &&
        cargo clippy --workspace --all-targets -- -D warnings &&
        cargo test --workspace)
      ;;
    android)
      step "android: check, assembleDebug"
      (cd "$ROOT/apps/android" && ./gradlew --console=plain check assembleDebug)
      ;;
    apple)
      [[ "$(uname -s)" == "Darwin" ]] || { skip "apple needs macOS"; return; }
      step "apple: core xcframework, swift test, xcodebuild"
      "$ROOT/scripts/build-core.sh" apple
      (cd "$ROOT/apps/apple/KeepAppleKit" && swift test --no-parallel) # the core keeps at most 16 sessions open at once
      (cd "$ROOT/apps/apple" && xcodegen generate --quiet &&
        xcodebuild -project VazieKeep.xcodeproj -scheme VazieKeep-iOS -destination generic/platform=iOS \
          -derivedDataPath build/DerivedData CODE_SIGNING_ALLOWED=NO -quiet build &&
        xcodebuild -project VazieKeep.xcodeproj -scheme VazieKeep-macOS -destination platform=macOS,arch=arm64 \
          -derivedDataPath build/DerivedData CODE_SIGNING_ALLOWED=NO -quiet build)
      ;;
    desktop)
      step "desktop: build (compile + tests)"
      (cd "$ROOT/apps/desktop" && ./gradlew --console=plain build)
      ;;
    extension)
      step "browser extension: wasm, format, lint, typecheck, build"
      "$ROOT/scripts/build-core.sh" wasm
      # The build needs the backend addresses, which are not in git. Without them this check builds with placeholders:
      # the result proves the build, it is never a package for a store.
      (
        export VAZIE_ACCOUNT_ORIGIN="${VAZIE_ACCOUNT_ORIGIN:-https://account.example.invalid}"
        export VAZIE_KEEP_ORIGIN="${VAZIE_KEEP_ORIGIN:-https://keep.example.invalid}"
        export VAZIE_SITE_ORIGIN="${VAZIE_SITE_ORIGIN:-https://site.example.invalid}"
        cd "$ROOT/apps/browser-extension" && pnpm_cmd install --frozen-lockfile && pnpm_cmd run check && pnpm_cmd run build
      )
      ;;
    *)
      echo "unknown area: $1 (expected: ${AREAS_ALL[*]} or all)" >&2
      exit 1
      ;;
  esac
}

while read -r area; do run_area "$area"; done < <(resolve_areas "$@")
step "All requested checks passed"
