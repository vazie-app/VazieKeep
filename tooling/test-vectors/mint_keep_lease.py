#!/usr/bin/env python3
"""Mints one signed Keep+ lease with OpenSSL, for checking that a build trusts the right keys.

    mint_keep_lease.py --key key.der --kid keep-lease-prod-2026-10 [--tier keep_plus] [--days 30]

`key.der` is an Ed25519 private key (PKCS#8 DER). Never commit one. This is a QA tool: the production
backend signs real leases, and this exists only so a release build can be tried against a lease signed
by the production key and against one signed by the QA key.
"""
import argparse
import base64
import json
import subprocess
import tempfile
import time
from pathlib import Path


def b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


parser = argparse.ArgumentParser()
parser.add_argument("--key", required=True)
parser.add_argument("--kid", required=True)
parser.add_argument("--subject", default="00000000-0000-4000-8000-000000000001")
parser.add_argument("--tier", default="keep_plus", choices=["free", "keep_plus"])
parser.add_argument("--days", type=int, default=30)
args = parser.parse_args()

now = int(time.time())
payload = {
    "version": 1, "issuer": "vazie", "subject": args.subject, "product": "keep", "tier": args.tier,
    "lifetime": False, "issued_at": now, "lease_expires_at": now + args.days * 86400,
    "entitlement_expires_at": None if args.tier == "free" else now + args.days * 86400,
    "features_version": 1, "revision": 1, "nonce": b64(str(now).encode()),
}
header = {"alg": "EdDSA", "kid": args.kid, "typ": "vazie-keep-lease+jws"}
signed = b64(json.dumps(header, separators=(",", ":")).encode()) + "." + b64(json.dumps(payload, separators=(",", ":")).encode())
with tempfile.TemporaryDirectory() as tmp:
    message = Path(tmp, "m")
    message.write_bytes(signed.encode())
    signature = subprocess.run(
        ["openssl", "pkeyutl", "-sign", "-inkey", args.key, "-keyform", "DER", "-rawin", "-in", str(message)],
        check=True, capture_output=True,
    ).stdout
print(signed + "." + b64(signature))
