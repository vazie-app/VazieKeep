# Keep+ lease public keys

The apps verify Keep+ leases against Ed25519 **public** keys listed here. Private keys never enter this
repository or any app.

- `production-keys.txt` - the keys release builds trust: `kid:publicKey` (raw 32 bytes, base64url, no
  padding), comma- or newline-separated. While it is empty, release builds accept no lease at all, so
  Keep+ stays off - which is the safe state until the production key exists.
- Debug builds additionally trust a QA key set in `apps/android/app/build.gradle.kts` and the Apple
  `project.yml`; a release build never does.

Making the production key (on the machine that will hold it, not on a laptop that syncs):

```bash
openssl genpkey -algorithm ed25519 -outform DER -out keep-lease.der
openssl pkey -inform DER -in keep-lease.der -pubout -outform DER | tail -c 32 | base64 | tr '+/' '-_' | tr -d '=\n'
base64 < keep-lease.der | tr -d '\n'     # the backend's KEEP_LEASE_SIGNING_KEY (a secret)
```

Put the first output next to a key id (e.g. `k1:...`) in `production-keys.txt`; give the second to the
backend as `KEEP_LEASE_SIGNING_KEY` with `KEEP_LEASE_SIGNING_KEY_ID=k1`. Rotation: add the new public key
as an extra line, ship the apps, then switch the backend's key id.
