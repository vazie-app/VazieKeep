#!/usr/bin/env bash
# Release audit for Keep+: nothing that could grant Keep+ may ship in an app.
# Checks the repository, and the release BuildConfig if a release build exists.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
fail=0
bad() { echo "AUDIT FAIL: $*" >&2; fail=1; }

# 1. No private key material anywhere in the tracked or untracked source tree.
if grep -rIl --exclude-dir=.git --exclude-dir=build --exclude-dir=target --exclude-dir=node_modules --exclude-dir=DerivedData --exclude-dir=.build \
     -e "BEGIN PRIVATE KEY" -e "BEGIN OPENSSH PRIVATE KEY" -e "KEEP_LEASE_SIGNING_KEY=" . | grep -v "audit-keep-plus.sh" | grep -q .; then
  bad "a private key or a lease signing key assignment is in the tree"
fi
if find . -path ./.git -prune -o \( -name "*.der" -o -name "qa-private*" -o -name "*.p8" \) -not -path "*/build/*" -not -path "*/target/*" -not -path "*/.build/*" -print | grep -q .; then
  bad "a key file (.der, qa-private*, .p8) is in the tree"
fi

# 2. No hardcoded Keep+ and no admin credentials in the app sources.
if grep -rIn --include='*.kt' --include='*.swift' -E "plusFeatures *= *true|developerPlus|forcePlus|isPlus *= *true" apps/android/app/src/main apps/apple/iOS apps/apple/KeepAppleKit/Sources 2>/dev/null | grep -q .; then
  bad "a hardcoded Keep+ switch is in an app"
fi
if grep -rIn --include='*.kt' --include='*.swift' -E "INTERNAL_API_TOKEN|/internal/v1" apps/android/app/src/main apps/apple/iOS apps/apple/KeepAppleKit/Sources 2>/dev/null | grep -q .; then
  bad "an app refers to the operator API"
fi

# 2b. The account code never logs: no logging call in the account modules at all.
if grep -rIn --include='*.kt' --include='*.swift' -E "Log\.[a-z]+\(|println\(|NSLog|os_log|Logger\(|print\(" \
     apps/android/app/src/main/kotlin/app/vazie/keep/account apps/apple/KeepAppleKit/Sources/KeepApp/Account.swift \
     apps/apple/KeepAppleKit/Sources/KeepApp/KeepEntitlementService.swift apps/apple/iOS/AccountView.swift apps/apple/iOS/AccountConfig.swift 2>/dev/null | grep -q .; then
  bad "the account code logs something"
fi

# 3. Release builds trust only production keys and have no developer sign-in.
for build in apps/android/app/build/generated/source/buildConfig/release/app/vazie/keep/BuildConfig.java; do
  if [[ -f "$build" ]]; then
    grep -q 'DEV_SIGN_IN = false' "$build" || bad "release BuildConfig allows developer sign-in"
    grep -q 'qa-1' "$build" && bad "release BuildConfig trusts the QA key"
    grep -q 'http://' "$build" && bad "release BuildConfig points at an http origin"
  fi
done
grep -q 'qa-' shared/keep-lease/production-keys.txt && bad "production-keys.txt lists a QA key"

((fail == 0)) && echo "Keep+ audit: ok"
exit "$fail"
