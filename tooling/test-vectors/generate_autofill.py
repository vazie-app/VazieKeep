"""Generate the synthetic Autofill vault in shared/test-vectors/autofill.

Every name, URL and password is invented. The domains are chosen to exercise the matching policy
(exact, subdomain, parent, look-alike, public suffix, IP). Run:

    .venv/bin/python tooling/test-vectors/generate_autofill.py [--lan-ip 192.168.1.20 --out PATH]

With --lan-ip and --out it writes a device QA vault whose "Local page" entries point at that
address, so a page served from the development machine can be tested in Chrome.
"""

import argparse
import hashlib
import os
import sys
from pathlib import Path

from pykeepass import create_database

sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate_kdbx4 import PASSWORD, kdf_parameters  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "shared" / "test-vectors" / "autofill"


def populate(kp, lan_ip):
    root = kp.root_group
    web = kp.add_group(root, "Sites")
    entries = [
        ("Example", "alice", "example-pass-1", "https://example.com/login"),
        ("Example (second account)", "bob", "example-pass-2", "example.com"),
        ("Accounts subdomain", "carol", "accounts-pass", "https://accounts.example.com"),
        ("PayPal", "dave", "paypal-pass", "https://www.paypal.com/signin"),
        ("Public suffix", "erin", "suffix-pass", "https://github.io"),
        ("Insecure", "frank", "http-only-pass", "http://plain.example.org"),
        ("Custom port", "gina", "port-pass", "https://example.org:8443"),
        ("Unicode host", "hana", "idn-pass", "https://bücher.example"),
        ("Facebook", "ivan", "facebook-pass", "https://facebook.com"),
        ("The Internet", "tomsmith", "SuperSecretPassword!", "https://the-internet.herokuapp.com"),
        ("Android app", "judy", "app-pass", "androidapp://com.example.app"),
        ("No URL", "kate", "bare-pass", ""),
    ]
    if lan_ip:
        entries += [
            ("Local page", "qa-user", "qa-password-1", f"http://{lan_ip}"),
            ("Local page (second account)", "qa-user-2", "qa-password-2", f"http://{lan_ip}"),
            ("Other host", "qa-other", "qa-password-3", "http://192.0.2.77"),
        ]
    for title, user, pw, url in entries:
        kp.add_entry(web, title, user, pw, url=url, force_creation=True)
    kp.add_entry(root, "Protected custom", "lena", "custom-pass", url="https://custom.example.net", force_creation=True).set_custom_property("PIN", "4321", protect=True)
    trashed = kp.add_entry(root, "Trashed example", "mallory", "trashed-pass", url="https://example.com/old", force_creation=True)
    kp.trash_entry(trashed)


def build(path, lan_ip=None):
    kp = create_database(str(path), password=PASSWORD)
    header = kp.kdbx.header.value
    header.minor_version = 0
    header.dynamic_header.cipher_id.data = "aes256"
    header.dynamic_header.encryption_iv.data = os.urandom(16)
    header.dynamic_header.kdf_parameters.data = kdf_parameters("argon2d")
    header.dynamic_header.compression_flags.data.compression = True
    kp.database_name = "Autofill Fixture"
    populate(kp, lan_ip)
    kp.save()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--lan-ip")
    parser.add_argument("--out")
    args = parser.parse_args()
    if args.out:
        build(Path(args.out), args.lan_ip)
        return
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / "autofill-fixture.kdbx"
    build(path)
    (OUT / "SHA256SUMS").write_text(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n")


if __name__ == "__main__":
    main()
