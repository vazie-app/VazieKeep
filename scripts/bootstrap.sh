#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

step "Rust"
if ! has rustup; then
  echo "rustup is missing: https://rustup.rs (then re-run this script)" >&2
  exit 1
fi
(cd "$ROOT/core" && rustup toolchain install)
targets=(wasm32-unknown-unknown)
case "$(uname -s)" in
  Darwin) targets+=(aarch64-apple-ios aarch64-apple-darwin aarch64-linux-android armv7-linux-androideabi x86_64-linux-android) ;;
  Linux) targets+=(aarch64-linux-android armv7-linux-androideabi x86_64-linux-android) ;;
esac
(cd "$ROOT/core" && rustup target add "${targets[@]}")

wasm_bindgen_version="$(sed -n 's/^wasm-bindgen = "=\(.*\)"/\1/p' "$ROOT/core/Cargo.toml")"
if ! wasm-bindgen --version 2>/dev/null | grep -q "$wasm_bindgen_version"; then
  cargo install wasm-bindgen-cli --locked --version "$wasm_bindgen_version"
fi
has cargo-ndk || cargo install cargo-ndk --locked

step "Android SDK"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
if [[ -d "$sdk" && ! -f "$ROOT/apps/android/local.properties" ]]; then
  echo "sdk.dir=$sdk" > "$ROOT/apps/android/local.properties"
  echo "wrote apps/android/local.properties"
elif [[ ! -d "$sdk" ]]; then
  skip "Android SDK not found; set ANDROID_HOME"
fi

if [[ "$(uname -s)" == "Darwin" ]]; then
  step "Apple"
  has xcodegen || echo "install XcodeGen: brew install xcodegen"
  "$ROOT/scripts/build-core.sh" apple
  has xcodegen && (cd "$ROOT/apps/apple" && xcodegen generate --quiet)
fi

step "Browser extension"
"$ROOT/scripts/build-core.sh" wasm
(cd "$ROOT/apps/browser-extension" && pnpm_cmd install --frozen-lockfile)

step "Done"
