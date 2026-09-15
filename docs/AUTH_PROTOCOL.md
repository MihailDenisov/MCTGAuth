# MCTGAuth Authentication Protocol V1

NeoForge payload bootstrap `bootstrap-1` carries explicit `AUTH_PROTOCOL_VERSION=1`; client and server release `0.5.0` use the `mctgauth` resource namespace. The wire contract and protocol version are unchanged from 0.4.2.

Payload IDs are `mctgauth:client_hello`, `server_hello`, `auth_challenge`, `auth_response`, `auth_success`, `auth_failure`, and `auth_state`.

The stable failure codes are `INVALID_CREDENTIALS`, `AUTH_TIMEOUT`, `TOO_MANY_ATTEMPTS`, `PROTOCOL_MISMATCH`, `CLIENT_MISSING`, `ACCOUNT_DISABLED`, `ACCOUNT_NOT_SYNCHRONIZED`, and `INTERNAL_AUTH_ERROR`. Release 0.5.0 keeps their existing wire ordinals and protocol version 1.

## Capability activation and compatibility

MCTGAuth payloads use the NeoForge play phase. The client registers them as optional, so installing MCTGAuth Client does not require an arbitrary server to implement the payload channel. The MCTGAuth Server keeps them required, so an MCTGAuth-enabled server may reject a missing or incompatible client before gameplay. This asymmetry is intentional.

At Minecraft login the client creates a fresh connection generation in `PASSIVE` state and sends no MCTGAuth payload. The server advertises `mctgauth:server_hello`; only a valid hello on the current non-integrated connection activates negotiation and allows the client to send `mctgauth:client_hello`. A challenge is accepted only after this activation. Absence of `server_hello` is normal for singleplayer and third-party servers and never becomes an authentication timeout or failure.

An in-memory connection is classified as the integrated singleplayer server and is ineligible for activation. Hostnames, IP address ranges, saved server entries, stored credentials, and unrelated mods are never capability signals.

The server resolves the connected player's authoritative Minecraft identity from its synchronized JSON cache. It sends a one-use UUID session, 32-byte random nonce, actual credential version, salt, bounded Argon2id parameters, and expiry. It never sends its stored derived verifier.

The client reads its locally stored password, calculates `K = Argon2id(password, salt, parameters)` away from the render thread, and returns `HMAC-SHA256(K, canonical_context)`. The context binds protocol version, nonce, session UUID, normalized connection identity, and credential version. The server uses `MessageDigest.isEqual`, consumes every challenge, and rejects stale credential versions and replayed responses.

V1 protects against plaintext password capture from custom payloads, response replay, arbitrary client account claims, stale credentials, and backend outage after durable sync. It does not protect a compromised client computer, Minecraft server, backend, or administrator. The server-side verifier is password-equivalent under this design. V1 is not PAKE or zero knowledge.
