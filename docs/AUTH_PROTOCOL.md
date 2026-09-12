# MCTGAuth Authentication Protocol V1

NeoForge payload bootstrap `bootstrap-1` carries explicit `AUTH_PROTOCOL_VERSION=1`; client and server release `0.4.1` use the `mctgauth` resource namespace.

Payload IDs are `mctgauth:client_hello`, `server_hello`, `auth_challenge`, `auth_response`, `auth_success`, `auth_failure`, and `auth_state`.

The stable failure codes are `INVALID_CREDENTIALS`, `AUTH_TIMEOUT`, `TOO_MANY_ATTEMPTS`, `PROTOCOL_MISMATCH`, `CLIENT_MISSING`, `ACCOUNT_DISABLED`, `ACCOUNT_NOT_SYNCHRONIZED`, and `INTERNAL_AUTH_ERROR`. Release 0.4.1 keeps their existing wire ordinals and protocol version 1 while replacing ambiguous internal names with the administrator-facing codes.

The server resolves the connected player's authoritative Minecraft identity from its synchronized JSON cache. It sends a one-use UUID session, 32-byte random nonce, actual credential version, salt, bounded Argon2id parameters, and expiry. It never sends its stored derived verifier.

The client reads its locally stored password, calculates `K = Argon2id(password, salt, parameters)` away from the render thread, and returns `HMAC-SHA256(K, canonical_context)`. The context binds protocol version, nonce, session UUID, normalized connection identity, and credential version. The server uses `MessageDigest.isEqual`, consumes every challenge, and rejects stale credential versions and replayed responses.

V1 protects against plaintext password capture from custom payloads, response replay, arbitrary client account claims, stale credentials, and backend outage after durable sync. It does not protect a compromised client computer, Minecraft server, backend, or administrator. The server-side verifier is password-equivalent under this design. V1 is not PAKE or zero knowledge.
