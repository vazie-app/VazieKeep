# ADR 0004: Separate iOS and macOS bundle identifiers in one Xcode project

- Status: Accepted
- Date: 2026-09-30

## Context

The brief asks for `app.vazie.keep` on iOS and `app.vazie.keep.macos` on macOS. Apple also lets
one multiplatform target (or two targets) share a single bundle id, which enables **Universal
Purchase**: one App Store record, one in-app purchase history across iOS and macOS.

## Decision

- Use **one Xcode project** (`apps/apple/project.yml`) with **two app targets**:
  - `VazieKeep-iOS`: `app.vazie.keep`
  - `VazieKeep-macOS`: `app.vazie.keep.macos`
- Put the shared code in the `KeepAppleKit` Swift package, not in a multiplatform app target.
- Plan the shared identifiers now, to be registered later:
  - App Group `group.app.vazie.keep`
  - Keychain access group `$(TeamIdentifierPrefix)app.vazie.keep.shared`
  
  The iOS app, the macOS app and their future AutoFill credential-provider extensions share data
  through these. Keychain sharing across different bundle ids is allowed within the same Team ID.

## Why separate ids are safe here

- Universal Purchase is the only feature that needs a shared bundle id. Vazie monetisation is
  planned around a Vazie account rather than App Store purchases, so we lose nothing today.
- Separate ids let the macOS app ship on its own cadence (it may arrive later than iOS) and
  outside the Mac App Store (Developer ID + notarization) if needed.
- Credential-provider extensions get clear, separate ids:
  `app.vazie.keep.autofill` and `app.vazie.keep.macos.autofill`.

## Consequences

- Switching to Universal Purchase later means changing the macOS bundle id **before** the first
  Mac App Store release. After that, the change is effectively irreversible. Revisit this ADR
  before the macOS store submission.
- Both targets are sandboxed. macOS uses App Sandbox with user-selected read/write file access and
  Hardened Runtime.
- No team is configured in the project. Local builds and CI use `CODE_SIGNING_ALLOWED=NO`, and
  signing is set up when distribution starts.
