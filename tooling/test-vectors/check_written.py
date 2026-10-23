"""Interop check: pykeepass (an independent KDBX implementation) reads a vault that Vazie Keep
edited and saved with examples/edit_and_save.rs, and confirms the edits and everything else.

    cargo run -p keep-core --example edit_and_save -- shared/test-vectors/editable/<f>.kdbx /tmp/out.kdbx "<password>"
    .venv/bin/python tooling/test-vectors/check_written.py /tmp/out.kdbx "<password>"
"""

import sys

from pykeepass import PyKeePass

from generate_editable import ATTACHMENT


def main():
    path, password = sys.argv[1], sys.argv[2]
    kp = PyKeePass(path, password=password)
    group = kp.find_groups(name="Interop Group", first=True)
    assert group is not None, "new group"
    entry = kp.find_entries(title="Interop Entry", first=True)
    assert entry.username == "interop-user" and entry.password == "interop-pässword <&>"
    assert entry.group.name == "Interop Group" and entry.tags == ["interop"]
    assert entry.get_custom_property("Interop Secret") == "s3cr3t"
    assert entry._element.find("String[Key='Interop Secret']/Value").get("Protected") == "True"

    mail = kp.find_entries(title="Example Mail", first=True)
    assert mail.username == "alice-edited@example.org" and mail.password == "hunter2-edited"
    assert mail.get_custom_property("Plain Note") is None, "removed field"
    assert len(mail.history) == 1 and mail.history[0].password == "hunter2-synthetic"
    assert mail._element.find("CustomData/Item/Value").text == "keep-this-too"

    bank = kp.find_entries(title="Bank", first=True)
    assert bank.password == "third-password" and len(bank.history) == 2
    files = kp.find_entries(title="With Attachment", first=True)
    assert files.attachments[0].filename == "notes.bin" and files.attachments[0].data == ATTACHMENT
    items = {i.findtext("Key"): i.findtext("Value") for i in kp.tree.findall("Meta/CustomData/Item")}
    assert items.get("VazieKeepFixture") == "preserve-me"
    print(f"OK {path}: {len(kp.entries)} entries, {len(kp.groups)} groups")


if __name__ == "__main__":
    main()
