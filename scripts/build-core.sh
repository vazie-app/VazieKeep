#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE="$ROOT/core"
PROFILE="${KEEP_CORE_PROFILE:-release}"
APPLE_KIT="$ROOT/apps/apple/KeepAppleKit"
WASM_OUT="$CORE/keep-core-wasm/pkg"

usage() {
  cat <<USAGE
Usage: scripts/build-core.sh <target>...

Targets:
  host    keep-core-ffi for the host (what desktop Gradle builds also do)
  apple   KeepCoreFFI.xcframework (iOS arm64 + macOS arm64) and Swift bindings
  wasm    keep-core-wasm for the browser extension (core/keep-core-wasm/pkg)
  all     apple + wasm (+ host)

Android and desktop bindings are produced by their Gradle builds, not by this script.
Set KEEP_CORE_PROFILE=debug for faster, unoptimised builds.
USAGE
}

cargo_profile_flag() {
  [[ "$PROFILE" == "release" ]] && echo "--release" || true
}

build_host() {
  (cd "$CORE" && cargo build -p keep-core-ffi $(cargo_profile_flag))
}

build_apple() {
  local ios_target=aarch64-apple-ios
  local mac_target=aarch64-apple-darwin
  export IPHONEOS_DEPLOYMENT_TARGET=18.0
  export MACOSX_DEPLOYMENT_TARGET=15.0

  (cd "$CORE" && cargo build -p keep-core-ffi --lib $(cargo_profile_flag) --target "$ios_target" --target "$mac_target")

  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN

  (cd "$CORE" && cargo run -q -p keep-core-ffi --features bindgen --bin uniffi-bindgen -- \
    generate --library "target/$mac_target/$PROFILE/libkeep_core_ffi.a" \
    --language swift --out-dir "$tmp/swift")

  mkdir -p "$tmp/headers"
  cp "$tmp/swift/KeepCoreFFI.h" "$tmp/headers/"
  cp "$tmp/swift/KeepCoreFFI.modulemap" "$tmp/headers/module.modulemap"

  rm -rf "$APPLE_KIT/Generated/KeepCoreFFI.xcframework"
  mkdir -p "$APPLE_KIT/Generated"
  xcodebuild -create-xcframework \
    -library "$CORE/target/$ios_target/$PROFILE/libkeep_core_ffi.a" -headers "$tmp/headers" \
    -library "$CORE/target/$mac_target/$PROFILE/libkeep_core_ffi.a" -headers "$tmp/headers" \
    -output "$APPLE_KIT/Generated/KeepCoreFFI.xcframework" >/dev/null

  mkdir -p "$APPLE_KIT/Sources/KeepCoreBindings"
  cp "$tmp/swift/KeepCoreBindings.swift" "$APPLE_KIT/Sources/KeepCoreBindings/KeepCoreBindings.swift"
  echo "apple: $APPLE_KIT/Generated/KeepCoreFFI.xcframework"
}

build_wasm() {
  local wasm_target=wasm32-unknown-unknown
  (cd "$CORE" && cargo build -p keep-core-wasm $(cargo_profile_flag) --target "$wasm_target")

  local expected actual
  expected="$(sed -n 's/^wasm-bindgen = "=\(.*\)"/\1/p' "$CORE/Cargo.toml")"
  actual="$(wasm-bindgen --version | awk '{print $2}')"
  if [[ "$expected" != "$actual" ]]; then
    echo "wasm-bindgen CLI $actual does not match crate $expected: cargo install wasm-bindgen-cli --locked --version $expected" >&2
    exit 1
  fi

  rm -rf "$WASM_OUT"
  wasm-bindgen --target web --out-dir "$WASM_OUT" \
    "$CORE/target/$wasm_target/$PROFILE/keep_core_wasm.wasm"
  printf '{ "type": "module" }\n' > "$WASM_OUT/package.json"
  echo "wasm: $WASM_OUT"
}

[[ $# -eq 0 ]] && { usage; exit 1; }

for target in "$@"; do
  case "$target" in
    host) build_host ;;
    apple) build_apple ;;
    wasm) build_wasm ;;
    all) build_host; build_apple; build_wasm ;;
    -h|--help) usage ;;
    *) usage; exit 1 ;;
  esac
done
