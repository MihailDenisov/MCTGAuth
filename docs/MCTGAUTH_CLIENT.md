# MCTGAuth Client

1. Register through the Telegram bot.
2. Wait for moderator approval and receive the generated password.
3. Start Minecraft and open **MCTGAuth** on the title screen, or use **Mods → MCTGAuth Client → Config**.
4. Paste the password and select **Save**.
5. Leave Automatic Authentication enabled and join the MCTGAuth server normally.

The password is plaintext in `.minecraft/config/mctgauth-client.json`. It is local to that installation and is not the same operation as changing the authoritative password through Telegram. The settings screen may change or clear the locally attempted password, but it never modifies the backend account.

The client is passive on every new connection. Singleplayer and servers without MCTGAuth continue normally with no authentication timeout, UI, proof payload, or Argon2id work. Automatic Authentication only responds after the current remote server advertises MCTGAuth with `server_hello`; a stored password is not an activation signal and is never sent to unrelated servers.

## Disconnect and reconnect behavior

MCTGAuth never calls Minecraft connection APIs and never schedules an automatic reconnect. A retryable `INVALID_CREDENTIALS` response opens the password editor and submits another proof over the same connection. Terminal failures stop the authentication lifecycle and invalidate delayed Argon2 work from that connection. The disconnect screen remains until the player explicitly returns or reconnects.

Terminal reasons include `ACCOUNT_NOT_FOUND`, `ACCOUNT_NOT_SYNCHRONIZED`, `ACCOUNT_DISABLED`, `INVALID_CREDENTIALS`, `CREDENTIAL_OUTDATED`, `AUTH_TIMEOUT`, `TOO_MANY_ATTEMPTS`, `PROTOCOL_MISMATCH`, `CLIENT_MISSING`, and safe verification/internal failures. If a new connection appears without an MCTGAuth `Starting reconnect` log entry, it was initiated outside MCTGAuth. MCTGAuth does not add hard dependencies on third-party reconnect mods, which may independently decide to reconnect.
