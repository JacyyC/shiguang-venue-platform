# Contributor instructions

This is the standalone public preparation of Shiguang Venue Platform.

- Read README.md, CONTRIBUTING.md and docs/limitations.md before changing behavior.
- Keep real credentials, personal records, runtime data and generated artifacts out of Git.
- Preserve Flyway migrations V1–V36 byte for byte. Add new migrations for schema changes.
- Preserve money calculations, authorization, reservation state machines and DEV-only funding boundaries unless the user explicitly requests a change.
- Record only checks that actually ran. Historical reports under engineering/reports are not validation of the current checkout.
- Run the checks appropriate to a change, plus python3 scripts/check_public_release.py and git diff --check before delivery.
- Use isolated synthetic databases and loopback services for verification. Do not reuse or stop unrelated processes or containers.
- Public publication, production deployment and real external payment/email/advertising require an explicit user request.
- The original private development repository and its human log are outside this repository's editing scope.
