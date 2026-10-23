# Design tokens

Extracted from `design/Vazie Keep — design system.html` (Foundations and Components) and checked
against the screen boards in `design/Core Flow Dark.html`, `design/Core Flow Light.html`,
`design/States & edge cases.html` and `design/Autofill.html`. Nothing here is invented: every value
is a number or colour that appears in the mockups.

Android: `apps/android/app/src/main/kotlin/app/vazie/keep/ui/designsystem`.
iOS: `apps/apple/KeepAppleKit/Sources/KeepDesignSystem`.

## Colour (semantic names)

| Token | Role | Dark | Light |
| --- | --- | --- | --- |
| `background` | ground | `#121014` | `#FAF8F4` |
| `surfaceList` | lists, cards | `#1A161D` | `#FFFEFC` |
| `surfaceField` | fields | `#231E27` | `#F1EEE9` |
| `surfaceRaised` | chips, dock, raised buttons | `#2D2732` | `#E8E4DE` |
| `surfaceToast` | toast, empty meter segments | `#393140` | `#DCD7D0` |
| `text` | primary text | `#F2ECE4` | `#211A25` |
| `textSecondary` | secondary text | `#B9AFBB` | `#5B5160` |
| `textTertiary` | placeholders, outlines | `#958B98` | `#6B6170` |
| `accent` | lavender: state, selection, links | `#C9B6F2` | `#5D43A8` |
| `accentContainer` | selected chip, dock tab, icon tiles | `#3A2F4F` | `#E6DDFA` |
| `onAccentContainer` | | `#E8DEFF` | `#2B1A5C` |
| `action` | cream "act", once per screen | `#F1E3C8` | `#2A2030` |
| `onAction` | | `#2A2018` | `#F6EEDD` |
| `caution` | clay: symbols, caution, destructive text | `#E9A77E` | `#A6532E` |
| `danger` | real failures only | `#F2A9A1` | `#A23B32` |
| `hairline` | row separators | white 7 % | `#221B26` 8 % |
| `floatingShadow` | dock, FAB, toast | black 45 % | `#342446` 16 % |

Monogram tones (container / content), dark then light:
clay `#3B271F/#EFB896` · `#F6DFD2/#86421F`, rose `#3A2131/#EDB5CF` · `#F6DCE7/#872D57`,
violet `#302748/#D2C0F7` · `#E6DDFA/#4B3590`, green `#213229/#A9D3BA` · `#DAEADF/#2E5A40`,
gold `#342C1E/#E6CE9B` · `#F1E5C9/#6A511C`, blue `#1E2A3A/#AEC7EA` · `#DCE6F5/#2A4C7A`.
Database tiles (same in both themes): `#644894/#C7B0F5`, `#50715F/#B5DBC5`, `#AB6647/#F6C6A8`,
`#384870/#AFC2EE`, `#BDA67F/#FFF3DA`. A key picks its monogram tone by a stable hash.

Secret characters are drawn in `text`, digits in `accent` and symbols in `caution`.

## Typography

Geologica for all interface text, Martian Mono (width 87.5) for technical data only. Both fonts are
OFL (`shared/fonts/OFL-*.txt`).

| Style | Size / line | Font | Tracking |
| --- | --- | --- | --- |
| display | 38.5 / 42 | Geologica 650 | −0.03 em |
| headline | 29 / 32.5 | Geologica 650 | −0.02 em |
| title | 22 / 26 | Geologica 600 | −0.012 em |
| bodyLarge | 17 / 24 | Geologica 550 | |
| body | 16 / 24 | Geologica 400 | |
| rowTitle | 16 / 22 | Geologica 550 | |
| rowSubtitle | 13.5 / 19 | Geologica 400 | |
| label | 13 / 18 | Geologica 600 | |
| caption | 13 / 18 | Geologica 400 | |
| badge | 12 / 16 | Geologica 600 | |
| button | 16 / 22 | Geologica 600 | |
| secret | 18.5 / 28 | Martian Mono 450 | +0.02 em |
| code | 24 / 32 | Martian Mono 450 | |
| mono | 12 / 17 | Martian Mono 400 | |

Android uses the variable fonts through `FontVariation`; iOS bundles static instances cut from them
(`shared/fonts`) and scales every style with Dynamic Type.

## Shape, spacing, elevation

Radii: badge 8, tile 12, inner button 16, chip 14, key 18, field 20, button 22, card 24, sheet and
hero 28, pill and dock fully round. Gutters 16, card padding 16–20, row height 64 (min), touch
targets ≥ 48 with a 44 visual size inside rows, 4 grid. Elevation is tone; only the dock, the add
button, toasts, dropdowns and sheets get a shadow.

## Components

| Component | Android | iOS |
| --- | --- | --- |
| Buttons: action, tonal, raised, text, caution; large 64, medium 52, pill 40 | `KeepButton` | `KeepButtonStyle` |
| Icon button 44 (48 target) | `KeepIconButton` | `KeepIconButtonStyle` |
| Show/Hide pill | `KeepActionPill` | `KeepActionPillStyle` |
| Fields (label above, 20 radius, lavender focus ring, error ring + message) | `KeepTextField`, `KeepSecureField` | `KeepFieldChrome`, `KeepTextField` |
| Card with hairline rows | `KeepCard`, `KeepHairline` | `KeepCard`, `KeepHairline` |
| Monogram | `Monogram` | `KeepMonogram` |
| Chip, badge, banner | `KeepChip`, `KeepBadge`, `KeepBanner` | `KeepChip`, `KeepBadge`, `KeepBanner` |
| Strength meter (five soft segments) | `KeepStrengthMeter` | `KeepStrengthMeter` |
| Secret text, hidden dots | `secretText`, `HiddenDots` | `KeepSecretText`, `KeepHiddenDots` |
| Dock with add button | `KeepDock` | `KeepDock` |
| Toast | `KeepSnackbarHost` | `KeepToast` |
| Settings row with icon tile | `KeepSettingRow` | `KeepSettingRow` |
| Dialog, sheet frame | `KeepDialog`, `AutofillSheet` | system alerts and sheets, tokens applied |
