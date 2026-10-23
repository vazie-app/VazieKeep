# Security Policy

## Reporting a vulnerability

Please report vulnerabilities **privately** through GitHub Security Advisories
("Security" tab → "Report a vulnerability") on this repository. Do not open a public issue.

Include the affected platform and version, the steps to reproduce, and the impact. **Never send a
real vault or real credentials.** Build a throwaway vault that shows the problem.

We aim to acknowledge reports within 7 days. Reporters are credited unless they prefer otherwise.

## Scope

The whole repository is in scope: the Rust core, the FFI/WASM boundaries, every client and the
build tooling. The project is pre-release, so there are no supported versions yet. Fixes land
on `main`.

## Design

The threat model, data-handling rules and known platform limitations are in
[docs/security/SECURITY_MODEL.md](docs/security/SECURITY_MODEL.md).
