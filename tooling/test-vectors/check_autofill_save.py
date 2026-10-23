"""Independent check (pykeepass) of the files written by AutofillSaveRealCoreTest.

    .venv/bin/python tooling/test-vectors/check_autofill_save.py apps/android/app/build/autofill-save

Updating a password must leave the previous one in the entry's history, and a created entry must
carry its title, username and origin URL.
"""

import sys
from pathlib import Path

from pykeepass import PyKeePass

PASSWORD = "correct horse battery staple"
folder = Path(sys.argv[1])

updated = PyKeePass(str(folder / "updated.kdbx"), password=PASSWORD)
entry = updated.find_entries(title="Example", first=True)
assert entry.password == "rotated-password", entry.password
assert entry.username == "alice"
assert entry.url == "https://example.com/login"
history = entry.history
assert len(history) == 1, len(history)
assert history[0].password == "example-pass-1", "history keeps the previous password"
assert history[0].title == "Example"
assert entry.mtime >= history[0].mtime
other = updated.find_entries(title="Example (second account)", first=True)
assert other.password == "example-pass-2" and not other.history

created = PyKeePass(str(folder / "created.kdbx"), password=PASSWORD)
new = created.find_entries(title="shop.example", first=True)
assert new.username == "newbie" and new.password == "fresh-password" and new.url == "https://shop.example"
assert not new.history
print("autofill save: history and created entry OK")
