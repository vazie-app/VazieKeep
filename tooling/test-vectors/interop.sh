#!/usr/bin/env bash
# Writer interop check against independent KDBX implementations, on synthetic vaults only.
#   pykeepass: needs the venv from generate_kdbx4.py (VENV=path/to/venv, default .venv)
#   KeePassXC: needs keepassxc-cli (brew install --cask keepassxc); skipped if missing
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
PW="correct horse battery staple"
VENV="${VENV:-$ROOT/.venv}"
CLI="${KEEPASSXC_CLI:-$(command -v keepassxc-cli || echo /Applications/KeePassXC.app/Contents/MacOS/keepassxc-cli)}"

for f in rich-aes-argon2d-gzip rich-chacha-aeskdf-plain-41 rich-no-recycle-bin; do
  (cd "$ROOT/core" && cargo run -q -p keep-core --example edit_and_save -- \
    "$ROOT/shared/test-vectors/editable/$f.kdbx" "$OUT/$f.kdbx" "$PW")
  if [[ -x "$VENV/bin/python" ]]; then
    (cd "$ROOT/tooling/test-vectors" && "$VENV/bin/python" check_written.py "$OUT/$f.kdbx" "$PW")
  fi
  if [[ -x "$CLI" ]]; then
    printf '%s\n' "$PW" | "$CLI" show -q -s "$OUT/$f.kdbx" "Interop Group/Interop Entry" | grep -q "interop-pässword <&>"
    printf '%s\n' "$PW" | "$CLI" attachment-export -q --stdout "$OUT/$f.kdbx" "Interop Group/Interop Entry" interop.txt | grep -q "attached by Vazie Keep"
    printf '%s\n' "$PW" | "$CLI" edit -q -u kpxc-user "$OUT/$f.kdbx" "Interop Group/Interop Entry"
    printf '%s\n' "$PW" | "$CLI" add -q -u kpxc-new -g -L 24 "$OUT/$f.kdbx" "Web/Added By KeePassXC"
    (cd "$ROOT/core" && cargo run -q -p keep-core --example dump_vault -- "$OUT/$f.kdbx" "$PW") \
      | grep -q "Added By KeePassXC | kpxc-new"
    echo "KeePassXC OK $f"
  fi
done
