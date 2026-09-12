# MCTGAuth 0.4.2 release notes

[Русская версия](RELEASE_NOTES_0.4.2_RU.md)

## Client activation hotfix

- MCTGAuth Client now begins every Minecraft connection in passive mode.
- Integrated singleplayer connections are detected through Minecraft's in-memory connection and cannot activate MCTGAuth.
- Third-party servers without MCTGAuth remain unaffected; absence of `server_hello` is not an error or timeout.
- The MCTGAuth Server now advertises `server_hello` before the client sends `client_hello`.
- Client payload registrations are optional, while MCTGAuth Server registrations remain required and server authentication remains fail-closed.
- Password UI and Argon2id work can begin only after capability activation and a valid challenge.
- Connection generations still invalidate delayed proof work after disconnect or server changes.

The wire payload structures and `AUTH_PROTOCOL_VERSION=1` are unchanged.

Deploy the 0.4.2 client and server together. A 0.4.2 client intentionally waits for the server-first capability advertisement, which a 0.4.1 server does not send until it receives the former client-first hello.

## Artifacts

```text
auth-backend/build/distributions/auth-backend-0.4.2.tar.gz
auth-neoforge-server/build/libs/mctgauth-server-0.4.2.jar
auth-neoforge-client/build/libs/mctgauth-client-0.4.2.jar
```
