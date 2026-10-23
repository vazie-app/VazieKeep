# ADR 0018: One design system for Android and iPhone

- Status: Accepted
- Date: 2026-10-05
- Source of truth: the boards in `design/`; values in [DESIGN_TOKENS](../design-system/DESIGN_TOKENS.md)
- Audit: [DESIGN_AUDIT](../design-system/DESIGN_AUDIT.md)

## Context

Until M11 both apps used Material and SwiftUI defaults with a palette that only resembled the
mockups (`#17131B` instead of `#121014`) and the system font. The mockups define one visual language:
tone-separated surfaces, a lavender state colour, a single cream "act" colour per screen, Geologica
and Martian Mono, fixed radii. The platforms must keep their own behaviour (Compose navigation and
Material dialogs, SwiftUI navigation and system alerts, the system biometric prompts) and look the same.

## Decision

- **Tokens first.** Colour, type, shape and spacing live in a design-system layer with semantic names
  (`surfaceList`, `accent`, `action`, `caution`), never `purple1`: Android `ui/designsystem`, iOS the
  SwiftPM target `KeepDesignSystem`, which the app and the AutoFill extension both link.
- **Components are restyled, not reinvented.** Compose keeps `BasicTextField`, `Switch`, `Slider`,
  `ModalBottomSheet` and `AlertDialog` with the tokens applied; SwiftUI keeps `NavigationStack`,
  `List` (for swipe actions), `Toggle`, `Menu`, system alerts and sheets.
- **Fonts.** Android bundles the OFL variable fonts and sets weight and width through
  `FontVariation`. iOS bundles static instances cut from them (`shared/fonts`) and registers them
  from the package bundle, scaling every style with Dynamic Type.
- **Accessibility contracts stay.** The masked secure field node, the revealed secret that is drawn
  but never exposed, and the identifiers the UI tests use are unchanged.
- **Autofill on Android is a bottom sheet** over the other app (translucent activity theme), as in
  `design/Autofill.html`; tapping outside does not dismiss it, because it may hold a half-typed
  master password.
- **No new features for the sake of the picture.** Where a board shows something the apps cannot do
  (database creation, favourites, passphrases), the audit lists it as a gap.

## Consequences

- Colours changed everywhere; screenshots and any golden images need to be retaken.
- `QA` instrumented tests click the unlock arrow by its content description, and by semantics rather
  than by coordinates, because the unlock form now moves with the keyboard.
- iOS rows of the vault list are cards drawn per row (first and last carry the rounded corners), so
  swipe actions and context menus keep working.
- The shipped APK grows by two font files (about 0.5 MB); the iOS app by seven static fonts (about 0.8 MB).

## Alternatives considered

- **Dynamic colour (Material You) on Android:** rejected as the default; the mockups define the
  palette. It remains an opt-in described in the Foundations board.
- **Compose Multiplatform for iOS:** rejected in ADR 0001; the apps stay native.
- **A shared token file generated for both platforms:** worth doing once there are more than two
  consumers; today the two small token files are kept in step by `DESIGN_TOKENS.md`.
