#!/usr/bin/env bash
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PATH="$HOME/.cargo/bin:$PATH"

step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
has() { command -v "$1" >/dev/null 2>&1; }
skip() { printf '\033[33mskip:\033[0m %s\n' "$*"; }

pnpm_cmd() {
  if has pnpm; then pnpm "$@"; else COREPACK_ENABLE_DOWNLOAD_PROMPT=0 corepack pnpm "$@"; fi
}

AREAS_ALL=(core android apple desktop extension)

resolve_areas() {
  if [[ $# -eq 0 || "$1" == "all" ]]; then
    printf '%s\n' "${AREAS_ALL[@]}"
  else
    printf '%s\n' "$@"
  fi
}
