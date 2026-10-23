# Screenshots

The README and store images are real screenshots of the Android release build with an invented demo database.

1. Demo databases (master password `demo`):

   ```bash
   python3 -m venv .venv && .venv/bin/pip install pykeepass==4.2.0 pillow
   .venv/bin/python tooling/screenshots/generate_demo_vault.py /tmp/keep-demo
   adb push "/tmp/keep-demo/Vazie Keep демо.kdbx" /sdcard/Download/
   ```

2. On the phone: Settings → «Allow screenshots» on; open the demo database. One free database is enough: for the other
   language, lock it, push the English file over the same name and unlock again.

3. Per language (`adb shell cmd locale set-app-locales app.vazie.keep --locales ru-RU` or `en-US`), one
   `adb exec-out screencap -p > <dir>/<name>.png` per screen:

   | File | Screen |
   |---|---|
   | `02-vault.png` | the database: groups and entries |
   | `03-entry.png` | «Northwind Bank» / «Северный банк», password hidden |
   | `04-generator.png` | Generator |
   | `05-appearance.png` | Settings → Appearance |
   | `06-settings.png` | Settings |
   | `07-vault-light.png` | the database in Ivory |

4. Compose:

   ```bash
   .venv/bin/python tooling/screenshots/compose.py <raw-en-dir> <raw-ru-dir>
   ```

   It paints the status and navigation bars over with the screen's background, so the phone's clock and
   notifications never reach the images. Afterwards reset the app language with `--locales ""`.
