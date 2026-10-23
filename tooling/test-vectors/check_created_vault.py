#!/usr/bin/env python3
"""Vault creation against KeePassXC: a Vazie-created vault opens there, an entry added there reads
back in Vazie, and a key file made by Vazie is accepted. Skips when keepassxc-cli is missing."""

import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

CORE = Path(__file__).resolve().parents[2] / "core"
CLI = shutil.which("keepassxc-cli")
if CLI is None:
    print("keepassxc-cli not found: skipped")
    sys.exit(0)

subprocess.run(
    ["cargo", "build", "-q", "-p", "keep-core", "--release", "--example", "create_vault", "--example", "inspect_vault"],
    cwd=CORE, check=True)
EX = CORE / "target" / "release" / "examples"


def run(args, **kw):
    return subprocess.run(args, capture_output=True, text=True, **kw)


with tempfile.TemporaryDirectory() as tmp:
    plain = Path(tmp) / "plain.kdbx"
    keyed = Path(tmp) / "keyed.kdbx"
    key = Path(tmp) / "k.keyx"
    env = dict(os.environ, VAZIE_PASSWORD="pa ss вод")
    subprocess.run([EX / "create_vault", plain, "Тест Personal", "balanced"], env=env, check=True)
    assert run([CLI, "ls", "-q", plain], input="pa ss вод\n").returncode == 0, "KeePassXC opens the new vault"
    info = run([CLI, "db-info", "-q", plain], input="pa ss вод\n").stdout
    assert "Argon2id" in info and "Recycle bin is enabled" in info, info
    added = run([CLI, "add", "-q", "-u", "bob", "-p", plain, "Edited in KeePassXC"], input="pa ss вод\nsecret\n")
    assert added.returncode == 0, added.stderr
    out = run([EX / "inspect_vault", plain], env=env).stdout
    assert "entries=1" in out and "Edited in KeePassXC" in out, out

    subprocess.run([EX / "create_vault", keyed, "Keyed", "stronger", key], env=dict(os.environ, VAZIE_PASSWORD="pw"), check=True)
    assert run([CLI, "ls", "-q", "-k", key, keyed], input="pw\n").returncode == 0, "KeePassXC accepts the Vazie key file"
    assert run([CLI, "ls", "-q", keyed], input="pw\n").returncode != 0, "the key file is required"
print("created vaults are KeePassXC-compatible")
