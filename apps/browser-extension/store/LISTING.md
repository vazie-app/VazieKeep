# Chrome Web Store listing

Everything the developer dashboard asks for, ready to paste. Images are in this folder: `en/` and `ru/` hold five
1280×800 screenshots each (JPEG), `promo/` holds the 440×280 small tile and the 1400×560 marquee in both languages.
Upload the package from `pnpm package` (`dist/vazie-keep-<version>-chromium.zip`).

## Store listing

| Field           | Value                                                                                                                             |
| --------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| Name            | Vazie Keep                                                                                                                        |
| Category        | Productivity (the store has no "security" category)                                                                               |
| Languages       | English (default), Russian                                                                                                        |
| Summary (≤ 132) | Password manager for the KeePass file you own. No account, no cloud: autofill, passkeys, TOTP and the full vault in your browser. |
| Summary, RU     | Менеджер паролей для вашего KeePass-файла. Без аккаунта и облака: автозаполнение, passkeys, TOTP и всё хранилище в браузере.      |
| Icon            | `icons/classic-128.png` (already in the package)                                                                                  |
| Website         | the Vazie website                                                                                                                 |
| Support         | e-mail from the Vazie website (the same address as in the app's About screen)                                                     |
| Privacy policy  | **You must give a public URL.** Publish the text in "Privacy" below on the site and paste its address here.                       |

### Description, English

Vazie Keep is a password manager that works with the KeePass (.kdbx) file you already have. The file stays where you
keep it, on your disk, and the extension opens it right in the browser. There is no Vazie cloud, no sync service and
no copy on our servers.

It is the whole of the Vazie Keep app, not just autofill:

• Autofill of logins in forms, with a menu in the field and a one-click fill from the toolbar or Ctrl/Cmd+Shift+L
• A prompt to save a new login or a changed password
• Passkeys: sign in and create passkeys, stored in your own KeePass file
• One-time codes (TOTP) with a live countdown, copied or filled in a click
• Create, edit, move and delete entries and groups, with tags, favourites, expiry dates, custom fields and icons
• Change history for every entry, with restore
• Attachments, several vaults at once, import from CSV, 1Password and Bitwarden
• A password generator with a strength meter
• Quick unlock with your device's passkey or security key (WebAuthn), and automatic lock on a timeout and when the device is locked
• Safe saving: a conflict check, five local backups and a read-back check, so the file is never half-written
• The clipboard is cleared after a delay you choose
• English and Russian, light and dark themes, popup, full tab and side panel

Source available (PolyForm Shield 1.0.0). The cryptography is the same Rust core as in the Android app, compiled to WebAssembly.
The extension has no ads, no analytics and no crash reporting. A Vazie Account is optional and exists only for Keep+.

Source code and security notes: https://github.com/vazie-app/VazieKeep

### Description, Russian

Vazie Keep — менеджер паролей, который работает с вашим KeePass-файлом (.kdbx). Файл остаётся там, где вы его
храните, на вашем диске, а расширение открывает его прямо в браузере. Нет облака Vazie, нет сервиса синхронизации,
нет копии на наших серверах.

Это всё приложение Vazie Keep целиком, а не только автозаполнение:

• Автозаполнение логинов с меню в поле и заполнением в один клик с панели или по Ctrl/Cmd+Shift+L
• Предложение сохранить новый логин или изменённый пароль
• Passkeys: вход и создание, хранятся в вашем KeePass-файле
• Одноразовые коды (TOTP) с обратным отсчётом: копирование и заполнение в один клик
• Создание, правка, перенос и удаление записей и групп: теги, избранное, срок действия, свои поля, значки
• История изменений каждой записи с восстановлением
• Вложения, несколько хранилищ одновременно, импорт из CSV, 1Password и Bitwarden
• Генератор паролей с оценкой стойкости
• Быстрая разблокировка passkey или ключом безопасности устройства (WebAuthn), автоблокировка по таймеру и при блокировке устройства
• Безопасное сохранение: проверка конфликта, пять локальных копий и проверка записанного, файл не бывает записан наполовину
• Буфер обмена очищается через выбранное время
• Русский и английский, светлая и тёмная темы, всплывающее окно, вкладка и боковая панель

Исходный код доступен (PolyForm Shield 1.0.0). Криптография — то же ядро на Rust, что и в приложении для Android, собранное в
WebAssembly. В расширении нет рекламы, аналитики и отправки отчётов о сбоях. Аккаунт Vazie необязателен и нужен только
для Keep+.

Исходный код и заметки о безопасности: https://github.com/vazie-app/VazieKeep

## Privacy practices tab

**Single purpose.** Vazie Keep opens and edits the user's own KeePass vault in the browser and fills its logins,
passkeys and one-time codes into the sites the user visits.

**Permission justifications** (paste one per field):

| Permission                                        | Justification                                                                                                                                                                                                                                                                                                                      |
| ------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `offscreen`                                       | Holds the unlocked vault and the WebAssembly cryptography core in an offscreen document, because a service worker is stopped by the browser and cannot keep a vault open or run the WASM core reliably.                                                                                                                            |
| `alarms`                                          | A once-a-minute tick that auto-locks the vault after the user's timeout and keeps the offscreen document alive.                                                                                                                                                                                                                    |
| `idle`                                            | Locks the vault when the user locks the device (the `locked` idle state).                                                                                                                                                                                                                                                          |
| `sidePanel`                                       | Optional side-panel view of the same app, opened from Settings.                                                                                                                                                                                                                                                                    |
| `contextMenus`                                    | "Fill login with Vazie Keep" in the page's right-click menu and "Lock Vazie Keep" in the toolbar button's menu.                                                                                                                                                                                                                    |
| `clipboardWrite`                                  | Copies a password, username or one-time code at the user's request and clears it after the chosen delay.                                                                                                                                                                                                                           |
| `clipboardRead`                                   | Read only to compare the clipboard with the value the extension copied before clearing it after the delay, so text the user copied later is never wiped.                                                                                                                                                                           |
| `unlimitedStorage`                                | Local copies of vault backups (five per vault) and cached site icons are stored in the extension's private storage; a vault with attachments can be large.                                                                                                                                                                         |
| Host permission `<all_urls>`                      | A password manager must detect login, sign-up and password-change forms and fill them on any site the user chooses, including in frames, and wrap `navigator.credentials` to offer passkeys. The page text is processed locally and only the page's address is matched against the vault; nothing about the page is sent anywhere. |
| Content scripts on all frames at `document_start` | The passkey wrapper must be installed before the page's own scripts run, otherwise sites call `navigator.credentials` first; form detection must run in the same frames the form lives in.                                                                                                                                         |

**Remote code.** None. All code is in the package. The page's `script-src` is `'self' 'wasm-unsafe-eval'`;
`wasm-unsafe-eval` is needed to run the bundled WebAssembly core, which is part of the package.

**Data usage disclosure** (tick only these, and certify the three statements):

- Authentication information — yes. The user's vault master password and keys are handled locally to open the vault;
  the optional Vazie Account signs in with an e-mail address. Passwords of the vault never leave the device.
- Personally identifiable information — yes, the e-mail address of the optional Vazie Account.
- Website content — no data is collected or sent; the extension reads page forms locally. Do not tick unless the
  dashboard asks about local processing.
- Everything else (health, financial, location, web history, user activity) — no.
- Certify: data is not sold to third parties; not used for purposes unrelated to the single purpose; not used for
  creditworthiness or lending.

## Privacy policy text

Publish this on the site (EN and RU) and use its address in the dashboard.

> **Vazie Keep browser extension: privacy**
>
> The vault is a file you choose on your device. The extension reads and writes it locally; it is never uploaded to
> Vazie or anyone else, and the master password, keys and entries never leave your device through Vazie.
>
> The extension contains no advertising, analytics or crash-reporting code and does not sell or share data.
>
> Network use: (1) only if you sign in to the optional Vazie Account, the extension talks to the Vazie Account service with your
> e-mail address and an account token to get the Keep+ entitlement; no vault data, entries, URLs or counts are sent;
> (2) it downloads the help texts and links from the Vazie website; (3) when you ask for site icons, it fetches the icon
> of an entry's own website over HTTPS without cookies and without a referrer.
>
> The extension stores its settings, a sealed account token, and up to five local backups of each vault in the
> browser's private extension storage on your device. Removing the extension removes them; your .kdbx files are not touched.
>
> Contact: the support e-mail shown on the Vazie website.

## Test instructions for the reviewer

No account is needed; the Vazie Account is optional.

1. Install, click the toolbar button, choose **Create a new vault**. A tab opens. Pick any place for the file,
   type a name and a master password, create it.
2. Add a login: **+ → Login**, title `Test`, username `demo`, password `demo-pass`, URL `https://example.com`.
3. Open any page with a sign-in form whose address matches the entry (edit the entry's URL to that site), click the
   login field and the shield button that appears, or press Ctrl+Shift+L (Cmd+Shift+L on Mac), to fill the stored login.
4. Lock with the lock icon and unlock again with the master password.
5. Optional: passkeys can be tried on https://webauthn.io (choose "Vazie Keep" in the prompt).
6. The extension asks for access to every site because it fills forms and wraps `navigator.credentials` on any site
   the user opens; nothing about a page leaves the device.

## Before each upload

1. Bump `version` in `package.json` (the manifest takes it from there); the store refuses an equal or lower version.
2. `node scripts/build.mjs && node scripts/package.mjs`, upload `dist/vazie-keep-<version>-chromium.zip`.
3. Do not upload a development build (`pnpm dev`): it trusts a QA lease key and opens overlay shadow roots.
