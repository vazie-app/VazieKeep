#!/usr/bin/env python3
"""Regenerates keep-lease/vectors.json: signed Keep+ leases for the Rust, Kotlin and Swift verifiers.

The signing key is derived from a fixed, public, test-only seed. It is not, and must never be, a
production key: the apps trust it only in debug and test builds. Signing uses `openssl pkeyutl`
(Ed25519, RFC 8032), so the vectors do not depend on any library under test.

    python3 tooling/test-vectors/generate_keep_lease.py
"""
import base64
import json
import os
import subprocess
import tempfile
from pathlib import Path

SEED = bytes.fromhex("5645525349454b454550544553544f4e4c59ffffffffffffffffffffffffffff"[:64])
KID = "test-1"
OTHER_KID = "test-2"
SUBJECT = "6f1c2f1a-8d4e-4a57-9b3a-0d2b1c3e4f50"
ISSUED = 1_790_000_000  # 2026-09-21T14:13:20Z, fixed so the vectors never depend on today
DAY = 86_400


def b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def der_private(seed: bytes) -> bytes:
    return bytes.fromhex("302e020100300506032b657004220420") + seed


def public_raw(seed: bytes) -> bytes:
    with tempfile.TemporaryDirectory() as tmp:
        key = Path(tmp, "k.der")
        key.write_bytes(der_private(seed))
        out = subprocess.run(
            ["openssl", "pkey", "-inform", "DER", "-in", str(key), "-pubout", "-outform", "DER"],
            check=True, capture_output=True,
        ).stdout
    return out[-32:]


def sign(message: bytes, seed: bytes) -> bytes:
    with tempfile.TemporaryDirectory() as tmp:
        key, msg = Path(tmp, "k.der"), Path(tmp, "m")
        key.write_bytes(der_private(seed))
        msg.write_bytes(message)
        return subprocess.run(
            ["openssl", "pkeyutl", "-sign", "-inkey", str(key), "-keyform", "DER", "-rawin", "-in", str(msg)],
            check=True, capture_output=True,
        ).stdout


def payload(**over):
    base = {
        "version": 1, "issuer": "vazie", "subject": SUBJECT, "product": "keep", "tier": "keep_plus",
        "lifetime": False, "issued_at": ISSUED, "lease_expires_at": ISSUED + 30 * DAY,
        "entitlement_expires_at": ISSUED + 90 * DAY, "features_version": 1, "revision": 3, "nonce": "AAAAAAAAAAAAAAAAAAAAAA",
    }
    base.update(over)
    return base


def token(pl, kid=KID, alg="EdDSA", seed=SEED, raw_header=None):
    header = raw_header or {"alg": alg, "kid": kid, "typ": "vazie-keep-lease+jws"}
    h = b64(json.dumps(header, separators=(",", ":")).encode())
    p = b64(json.dumps(pl, separators=(",", ":")).encode())
    return f"{h}.{p}.{b64(sign(f'{h}.{p}'.encode(), seed))}"


def tamper(tok, old, new):
    h, p, s = tok.split(".")
    body = base64.urlsafe_b64decode(p + "=" * (-len(p) % 4)).decode().replace(old, new)
    return f"{h}.{b64(body.encode())}.{s}"


plus = token(payload())
cases = [
    {"name": "plus_90_days", "token": plus, "valid": True, "tier": "keep_plus", "lifetime": False},
    {"name": "free", "token": token(payload(tier="free", entitlement_expires_at=None)), "valid": True, "tier": "free", "lifetime": False},
    {"name": "lifetime", "token": token(payload(lifetime=True, entitlement_expires_at=None)), "valid": True, "tier": "keep_plus", "lifetime": True},
    {"name": "ends_in_5_days", "token": token(payload(lease_expires_at=ISSUED + 5 * DAY, entitlement_expires_at=ISSUED + 5 * DAY)), "valid": True, "tier": "keep_plus", "lifetime": False},
    {"name": "tier_tampered", "token": tamper(plus, "keep_plus", "keep_plux"), "valid": False},
    {"name": "lifetime_tampered", "token": tamper(plus, '"lifetime":false', '"lifetime":true '), "valid": False},
    {"name": "signed_by_another_key", "token": token(payload(), seed=bytes([7]) * 32), "valid": False},
    {"name": "unknown_kid", "token": token(payload(), kid="nobody"), "valid": False},
    {"name": "alg_none", "token": token(payload(), alg="none"), "valid": False},
    {"name": "alg_hs256", "token": token(payload(), alg="HS256"), "valid": False},
    {"name": "wrong_issuer", "token": token(payload(issuer="evil")), "valid": False},
    {"name": "wrong_product", "token": token(payload(product="vpn")), "valid": False},
    {"name": "future_version", "token": token(payload(version=2)), "valid": False},
    {"name": "lease_outlives_entitlement", "token": token(payload(lease_expires_at=ISSUED + 40 * DAY, entitlement_expires_at=ISSUED + 10 * DAY)), "valid": False},
    {"name": "lifetime_with_end", "token": token(payload(lifetime=True)), "valid": False},
    {"name": "truncated", "token": plus.rsplit(".", 1)[0], "valid": False},
    {"name": "garbage", "token": "not.a.token", "valid": False},
    {"name": "empty", "token": "", "valid": False},
]

out = {
    "about": "Test-only Keep+ lease vectors. Regenerate with tooling/test-vectors/generate_keep_lease.py.",
    "issued_at": ISSUED,
    "subject": SUBJECT,
    "keys": [{"kid": KID, "public_key": b64(public_raw(SEED))}],
    "unused_key": {"kid": OTHER_KID},
    "cases": cases,
}
dest = Path(__file__).resolve().parents[2] / "shared" / "test-vectors" / "keep-lease" / "vectors.json"
dest.write_text(json.dumps(out, indent=2) + "\n")
print(f"wrote {dest} ({len(cases)} cases)")
