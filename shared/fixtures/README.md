# Fixtures

Synthetic demo data for UI previews, screenshots, manual testing and sample vaults. Test vectors
(inputs with a single correct output) belong in [`../test-vectors`](../test-vectors) instead.

> **Never commit real vaults, passwords, keys or personal data.**

Every fixture must be:

- **Invented.** Names, e-mails, URLs and notes are fake. Use `example.com`, `example.org` and
  `*.test` domains.
- **Documented.** A vault fixture comes with a note listing its password or key file, the tool
  that produced it, and what it contains.
- **Small.** No fixture needs more than a few hundred KiB.

`.kdbx` and `.keyx` files are git-ignored everywhere except under `shared/fixtures/` and
`shared/test-vectors/`, so a vault opened by accident somewhere else can't be committed.
