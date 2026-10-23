#!/usr/bin/env bash
# Lists the Rust crates compiled into the extension's WebAssembly (build-time proc macros left out),
# one "name version" per line, for THIRD_PARTY_NOTICES.md and the licence texts packed into the zip.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
(cd "$HERE/../../core" && cargo tree -p keep-core-wasm -e normal,no-proc-macro \
  --target wasm32-unknown-unknown --prefix none) \
  | sed 's/ (.*//; s/ v/ /' | grep -v '^keep-core' | sort -u > "$HERE/wasm-crates.txt"
echo "wasm-crates.txt: $(wc -l < "$HERE/wasm-crates.txt") crates"
