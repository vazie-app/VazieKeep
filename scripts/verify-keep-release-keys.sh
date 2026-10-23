#!/usr/bin/env bash
# Proves a release build trusts the production lease key and nothing else.
#
#   KEEP_PROD_KEY_DER=~/.vazie-secrets/keep-lease-prod/private.der \
#   KEEP_QA_KEY_DER=/path/to/qa-private.der  scripts/verify-keep-release-keys.sh [apple-release-info.plist]
#
# 1. the keys compiled into the Android release BuildConfig (and, if given, an iOS release Info.plist)
#    are exactly shared/keep-lease/production-keys.txt - no QA key;
# 2. a lease signed by the production key is accepted with those keys;
# 3. a lease signed by the QA key, one that claims the production kid, and a tampered one are refused.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PATH="$HOME/.cargo/bin:$PATH"
PROD_KEY="${KEEP_PROD_KEY_DER:?set KEEP_PROD_KEY_DER}"
QA_KEY="${KEEP_QA_KEY_DER:?set KEEP_QA_KEY_DER}"
expected="$(grep -v '^[[:space:]]*#' "$ROOT/shared/keep-lease/production-keys.txt" | tr -d ' \r' | paste -sd, -)"
kid="${expected%%:*}"
fail=0
check() { if [[ "$2" == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1 (got: $3)"; fail=1; fi; }

android="$ROOT/apps/android/app/build/generated/source/buildConfig/release/app/vazie/keep/BuildConfig.java"
if [[ -f "$android" ]]; then
  got="$(sed -n 's/.*KEEP_LEASE_KEYS = "\(.*\)";/\1/p' "$android")"
  check "Android release trusts exactly the production key list" "$expected" "$got"
fi
if [[ -n "${1:-}" ]]; then
  got="$(/usr/libexec/PlistBuddy -c 'Print :VazieKeepLeaseKeys' "$1")"
  check "iOS release trusts exactly the production key list" "$expected" "$got"
fi

verify() { (cd "$ROOT/core" && cargo run -q -p keep-core --example verify_lease -- "$expected" "$1" 2>&1 | head -1) || true; }
mint() { python3 "$ROOT/tooling/test-vectors/mint_keep_lease.py" "$@"; }

check "a lease signed by the production key is accepted" "accepted: keep_plus $kid" "$(verify "$(mint --key "$PROD_KEY" --kid "$kid")")"
check "a lease signed by the QA key (kid qa-1) is refused" "rejected: UnknownKey" "$(verify "$(mint --key "$QA_KEY" --kid qa-1)")"
check "a QA-signed lease claiming the production kid is refused" "rejected: BadSignature" "$(verify "$(mint --key "$QA_KEY" --kid "$kid")")"
tok="$(mint --key "$PROD_KEY" --kid "$kid")"
tampered="$(python3 - "$tok" <<'PY'
import sys
h, p, s = sys.argv[1].split(".")
i = len(p) // 2
print(".".join([h, p[:i] + ("B" if p[i] == "A" else "A") + p[i + 1:], s]))
PY
)"
check "a tampered production lease is refused" "rejected: BadSignature" "$(verify "$tampered")"
exit "$fail"
