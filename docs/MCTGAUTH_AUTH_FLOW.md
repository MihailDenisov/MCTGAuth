# MCTGAuth Authentication Flow

```text
Telegram registration and approval
        ↓
backend creates Argon2id verifier and sync event
        ↓ outbound HTTPS poll
server atomically updates mctgauth/accounts.json, then ACKs
        ↓
client joins with password from local config
        ↓ nonce/session challenge
client derives Argon2id key and sends HMAC proof
        ↓
server verifies locally and releases quarantine
```

Normal login never requires Telegram/backend availability or `/login`. Unknown players are denied; Minecraft cannot create accounts. Password changes remain a Telegram/backend operation. After rotation and synchronization, an automatic attempt using the old local password is rejected and the client opens its editor for **Save and authenticate**.

`INVALID_CREDENTIALS` may issue another challenge within the same connection while attempts remain. Every other terminal result disconnects fail-closed. MCTGAuth performs no automatic Minecraft reconnect; a later connection must be initiated manually or by software outside this mod. Connection-generation checks prevent delayed client authentication work from an earlier connection from sending a proof into a later lifecycle.
