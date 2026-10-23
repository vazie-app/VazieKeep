#!/usr/bin/env bash
# Cuts the toolbar icons from the brand artwork in keep_icons (macOS: uses sips).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE="$HERE/../../keep_icons"
OUT="$HERE/icons"
mkdir -p "$OUT"
declare -a COLORS=("classic:icon_keep" "blue:keep_blue" "gray:keep_gray" "green:keep_green" "pink:keep_pink" "red:keep_red" "violet:keep_violet" "yellow:keep_yellow")
for pair in "${COLORS[@]}"; do
  name="${pair%%:*}"
  file="${pair##*:}"
  for size in 16 32 48 128; do
    sips -z "$size" "$size" "$SOURCE/$file.png" --out "$OUT/$name-$size.png" >/dev/null
  done
done
echo "icons: $OUT"
