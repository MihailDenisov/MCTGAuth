---
name: security-review
description: Use for authentication, password changes, secrets, moderator authorization, sync API security, or personal-data handling.
---
# Security Review

Read `docs/architecture/SECURITY_MODEL.md`.

Check: no plaintext password persistence/logging; modern password hashing; replay resistance; Telegram authorization by numeric ID; ownership checks; rate limits; HTTPS/authenticated sync; idempotency; secret redaction; minimal personal-data replication; safe error messages; failure behavior during backend outage.

For any credential/protocol change, add tests for invalid credentials, replay/duplicate behavior where applicable, unauthorized actors, stale versions, and retry/idempotency.
