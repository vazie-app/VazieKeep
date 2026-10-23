"""Generate the synthetic key-file vectors in shared/test-vectors/keyfiles.

Every password, key file and value below is invented test data. Run from the repository root:

    .venv/bin/python tooling/test-vectors/generate_keyfiles.py

Key files are deterministic (derived from fixed labels). The vaults are written by pykeepass,
whose key-file parser is independent of ours, and every key component computed here from the
KeePass 2.x rules is checked against pykeepass before anything is written.
"""

import base64
import hashlib
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from generate_kdbx4 import PASSWORD, WRONG_PASSWORD, kdf_parameters  # noqa: E402
from pykeepass import create_database  # noqa: E402
from pykeepass.kdbx_parsing.common import compute_key_composite  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "shared" / "test-vectors" / "keyfiles"

ENTRY = {"title": "Key File Entry", "username": "kf-user", "password": "kf-pass-synthetic", "url": "https://keyfile.example.org"}


def synthetic(label, length=32):
    out = b""
    counter = 0
    while len(out) < length:
        out += hashlib.sha256(f"vazie-keep test key file {label} {counter}".encode()).digest()
        counter += 1
    return out[:length]


def xml_v2(key, hash_hex=None):
    hex_key = key.hex().upper()
    lines = []
    for row in range(0, len(hex_key), 32):
        chunk = hex_key[row:row + 32]
        lines.append("\t\t\t" + " ".join(chunk[i:i + 8] for i in range(0, len(chunk), 8)))
    hash_hex = hash_hex or hashlib.sha256(key).digest()[:4].hex().upper()
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n<KeyFile>\n\t<Meta>\n\t\t<Version>2.0</Version>\n\t</Meta>\n'
        f'\t<Key>\n\t\t<Data Hash="{hash_hex}">\n' + "\n".join(lines) + "\n\t\t</Data>\n\t</Key>\n</KeyFile>"
    ).encode()


def xml_v1(key):
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n<KeyFile>\n\t<Meta>\n\t\t<Version>1.00</Version>\n\t</Meta>\n'
        f"\t<Key>\n\t\t<Data>{base64.b64encode(key).decode()}</Data>\n\t</Key>\n</KeyFile>"
    ).encode()


KEY_FILES = {
    "keyfile-v2.keyx": ("xml-v2", synthetic("v2"), lambda k: xml_v2(k)),
    "keyfile-v1.key": ("xml-v1", synthetic("v1"), lambda k: xml_v1(k)),
    "keyfile-binary32.key": ("binary-32", synthetic("binary32"), lambda k: k),
    "keyfile-hex64.key": ("hex-64", synthetic("hex64"), lambda k: k.hex().upper().encode()),
    "keyfile-hashed.bin": ("hashed", None, lambda _: synthetic("arbitrary file", 1000)),
    "keyfile-wrong.keyx": ("xml-v2", synthetic("wrong"), lambda k: xml_v2(k)),
}

BAD_KEY_FILES = {
    "keyfile-v2-bad-hash.keyx": xml_v2(synthetic("v2"), hash_hex="DEADBEEF"),
}


def component(file_bytes, key, fmt):
    return hashlib.sha256(file_bytes).digest() if fmt == "hashed" else key


def build(name, password, key_file):
    path = OUT / name
    kp = create_database(str(path), password=password, keyfile=str(OUT / key_file) if key_file else None)
    header = kp.kdbx.header.value
    header.dynamic_header.kdf_parameters.data = kdf_parameters("argon2d")
    kp.database_name = "Key File Vault"
    kp.add_entry(kp.root_group, ENTRY["title"], ENTRY["username"], ENTRY["password"], url=ENTRY["url"], force_creation=True)
    kp.save()


def vector(file, password, key_file, expect):
    return {"file": file, "password": password, "key_file": key_file, "expect": expect}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.iterdir():
        old.unlink()

    key_files = {}
    for name, (fmt, key, render) in KEY_FILES.items():
        data = render(key)
        (OUT / name).write_bytes(data)
        expected = component(data, key, fmt)
        if compute_key_composite(None, str(OUT / name)) != hashlib.sha256(expected).digest():
            raise SystemExit(f"pykeepass disagrees on {name}")
        key_files[name] = {"format": fmt, "component": expected.hex()}
    for name, data in BAD_KEY_FILES.items():
        (OUT / name).write_bytes(data)
        key_files[name] = {"format": "xml-v2", "error": "invalid_key_file"}

    build("vault-password-only.kdbx", PASSWORD, None)
    build("vault-keyfile-only.kdbx", None, "keyfile-v2.keyx")
    build("vault-password-plus-keyfile.kdbx", PASSWORD, "keyfile-v2.keyx")
    for fmt_file, vault in [
        ("keyfile-v1.key", "vault-keyfile-xml-v1.kdbx"),
        ("keyfile-binary32.key", "vault-keyfile-binary32.kdbx"),
        ("keyfile-hex64.key", "vault-keyfile-hex64.kdbx"),
        ("keyfile-hashed.bin", "vault-keyfile-hashed.kdbx"),
    ]:
        build(vault, None, fmt_file)

    ok = {"contents": "keyfile"}
    wrong = {"error": "invalid_credentials"}
    vectors = [
        vector("vault-password-only.kdbx", "standard", None, ok),
        vector("vault-password-only.kdbx", "wrong", None, wrong),
        vector("vault-password-only.kdbx", "standard", "keyfile-v2.keyx", wrong),
        vector("vault-keyfile-only.kdbx", None, "keyfile-v2.keyx", ok),
        vector("vault-keyfile-only.kdbx", None, "keyfile-wrong.keyx", wrong),
        vector("vault-keyfile-only.kdbx", "standard", "keyfile-v2.keyx", wrong),
        vector("vault-keyfile-only.kdbx", None, "keyfile-v2-bad-hash.keyx", {"error": "invalid_key_file"}),
        vector("vault-password-plus-keyfile.kdbx", "standard", "keyfile-v2.keyx", ok),
        vector("vault-password-plus-keyfile.kdbx", "wrong", "keyfile-v2.keyx", wrong),
        vector("vault-password-plus-keyfile.kdbx", "standard", "keyfile-wrong.keyx", wrong),
        vector("vault-password-plus-keyfile.kdbx", "standard", None, wrong),
        vector("vault-password-plus-keyfile.kdbx", None, "keyfile-v2.keyx", wrong),
        vector("vault-password-plus-keyfile.kdbx", None, None, {"error": "missing_credentials"}),
        vector("vault-keyfile-xml-v1.kdbx", None, "keyfile-v1.key", ok),
        vector("vault-keyfile-binary32.kdbx", None, "keyfile-binary32.key", ok),
        vector("vault-keyfile-hex64.kdbx", None, "keyfile-hex64.key", ok),
        vector("vault-keyfile-hashed.kdbx", None, "keyfile-hashed.bin", ok),
    ]

    checksums = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(OUT.iterdir())}
    (OUT / "SHA256SUMS").write_text("".join(f"{h}  {n}\n" for n, h in checksums.items()))
    suite = {
        "suite": "kdbx4-keyfiles",
        "version": 1,
        "description": "Synthetic KDBX 4 vaults protected by password, key file, or both, written by pykeepass 4.2.0 through tooling/test-vectors/generate_keyfiles.py. Key files are derived from fixed labels. All passwords, key files and contents are invented test data.",
        "passwords": {"standard": PASSWORD, "wrong": WRONG_PASSWORD},
        "key_files": key_files,
        "contents": {"keyfile": {"name": "Key File Vault", "entries": [ENTRY]}},
        "vectors": vectors,
    }
    (OUT / "vectors.json").write_text(json.dumps(suite, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps(checksums, indent=2))


if __name__ == "__main__":
    main()
