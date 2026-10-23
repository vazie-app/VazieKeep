#!/usr/bin/env bash
# Writes the Keep+ lease public keys the app trusts into the built Info.plist (VazieKeepLeaseKeys).
#
# Release builds trust only shared/keep-lease/production-keys.txt (empty until the production key
# exists, so Keep+ stays off). Debug builds additionally trust the QA public key. Public keys only.
set -euo pipefail

PLIST="${TARGET_BUILD_DIR}/${INFOPLIST_PATH}"
KEYS="$(grep -v '^[[:space:]]*#' "${SRCROOT}/../../shared/keep-lease/production-keys.txt" | tr -d ' \r' | paste -sd, - || true)"
if [[ "${CONFIGURATION}" == "Debug" ]]; then
  QA="qa-1:jvdHCl0JnGQgmmBFk1gSU5-VhQaBPSLL8aEtk2m8e4o"
  KEYS="${QA}${KEYS:+,${KEYS}}"
fi
/usr/libexec/PlistBuddy -c "Delete :VazieKeepLeaseKeys" "${PLIST}" 2>/dev/null || true
/usr/libexec/PlistBuddy -c "Add :VazieKeepLeaseKeys string ${KEYS}" "${PLIST}"
