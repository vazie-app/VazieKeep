# M12 design audit

The boards in `design/` are the source of truth. This audit compares each of them with the
Android and iOS apps **before** M12 and lists what was done. "Gap" means the board shows
something the app has no feature for; those are listed at the end and were not invented.

## Files covered

| File | Boards | What it gave M12 |
| --- | --- | --- |
| `Vazie Keep — design system.html` | Foundations, Components | every token, type scale, shape, component rule |
| `Core Flow Dark.html` / `Core Flow Light.html` | Welcome, new database (2), Unlock, Vault, Switcher, Search (2), Entry, New entry, Generator (2), Database settings, Missing file | screen layouts in both themes |
| `States & edge cases.html` | keyboard, fingerprint not recognised, wrong password, opening, read-only vault, empty vault, unsaved changes, revealed + copied, 130 % text, tablet | error, empty, progress and read-only states |
| `Autofill.html` | locked, suggest, sheet, save | Autofill bottom sheets (Android) |
| `Vazie Keep · профиль и настройки — общий паттерн Vazie Account.html` | Settings (dark/light), profile, signed-out profile | settings structure: icon tile + title + subtitle + trailing control in cards |
| `Keep+.html`, `Keep+ Light.html`, `Keep+ · дизайн-система и состояния.html` | paywall, plans, purchase, restore, expiry, errors | components only (banners, badges, pills); the paywall itself is the Keep+ milestone |

## Screen by screen

| Design screen / component | Android before | iOS before | Difference | Action |
| --- | --- | --- | --- | --- |
| Foundations (colour, type, shape) | approximate palette (`#17131B` ground, Material defaults), system font | approximate palette, system font | wrong values, no Geologica/Martian Mono | exact tokens, bundled fonts, `designsystem` / `KeepDesignSystem` |
| Welcome / chooser | centred icon, tagline, Material button | SF Symbol, title, prominent button | no display headline, no trust card | display headline, three-row trust card, cream action |
| Unlock | top bar + card + outlined field + Material button | scroll form with vault card | design is bottom-anchored: tile, name, key-file chip, field with arrow, fingerprint/Face ID tonal | rebuilt on both |
| Unlock — wrong password / opening | banner above | banner above | inline error under the field, progress + "Deriving the key…" | rebuilt on both |
| Key file UI | text rows with buttons | text rows with buttons | chip "Key file name ✓" with Change/Remove | chip on both |
| Vault browser | top bar, list rows, FAB | `List` with sections, `+` menu | header with tile, count, Lock pill; search field; cards with monograms and copy; dock with add | rebuilt on both (groups instead of filter chips) |
| Search | outlined field, flat list | `.searchable` | field in the design's chrome; results as a card | rebuilt on both |
| Entry detail | stacked surface cards, icon buttons | `List` sections | one card with hairlines; password row with dots, Hidden/Visible badge, Show pill, copy; "More fields" card; tag badges | rebuilt on both |
| Entry editor | outlined fields, text buttons | `Form` | monogram + title row, labelled fields, strength meter, group row, field cards | rebuilt on both |
| Password generator | modal sheet with switches | `Form` sheet | big coloured password, strength meter, dice + primary, length stepper + slider, keycaps, look-alike switch | shared `GeneratorPanel`; new Generator tab on both |
| Settings | flat rows | `Form` | cards with icon tiles, values and switches | rebuilt on both |
| Biometrics | switch row | switch row + footers | icon tile row, status in the subtitle | restyled on both |
| Alternate icon picker | dialog with grid | grid in the form | grid inside a card, accent ring on selection | restyled on both |
| Conflict states (changed elsewhere) | `AlertDialog` | system alert | tokens on dialogs; banners for unsaved / recovered | `KeepDialog`, `KeepBanner`; iOS keeps system alerts |
| Recovery states | banners | banners | banner with raised pill actions | `KeepBanner` with `Raised` / `Action` pills |
| Autofill-owned screens | full-screen activity | credential provider list | **bottom sheet** over the other app: avatar tile, title, mono subtitle, search, rows, "Save to…" rows | `AutofillSheet` (Android); iOS extension restyled (system presents it) |
| Empty states | icon + text | text only | tinted icon tile, title, message, action | `EmptyState` / `emptyState` |
| Error states | tinted banner | red/pink banner | clay-rose text with icon on a field-coloured card, red only for real failures | `KeepBanner` tones |
| Toast | Material snackbar | inline banner | rises from the bottom, drains for the clipboard timeout | toast on both (no drain line yet) |

## Gaps: in the design, not in the app

| Design | Why it is not built |
| --- | --- |
| Create a new database, database switcher, missing-file screen | no database creation or multi-database model yet |
| Entry-type filter chips (Logins, Cards, Notes) | no entry types in the core; favourites, expiry and tags have chips |
| Passphrase generator tab | the core generates passwords only |
| Passkeys, Keep+ paywall, Vazie Account profile | later milestones |
| Tablet / landscape three panes | not started |
| Mascot illustrations ("VAZI" placeholders) | art is not part of the repository |
| Toast drain line, switch thumb with a check on iOS | native `Toggle` is kept on iOS |
