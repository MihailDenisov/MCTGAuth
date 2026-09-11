# Password Change Flow

Only the owner (or explicitly authorized moderator workflow) may rotate a Minecraft account password. Apply a cooldown/rate limit. Generate the password with `SecureRandom`. Never log it.

Recommended consistency rule: create a pending credential version, send a sync event, activate it after Minecraft acknowledgement, then present/confirm activation to the user. If implementation instead reveals the password before acknowledgement, clearly model pending/active versions so outages cannot leave the user uncertain about which password works.

## Phase 2 behavior

The backend verifies Telegram ownership, enforces a ten-minute minimum interval and five rotations per rolling 24 hours, generates a password with `SecureRandom`, stores only its Argon2id verifier, increments `credential_version`, and creates `CREDENTIAL_CHANGED`.

The bot tells the user that the new credential becomes usable after Minecraft synchronization. The future server consumer must apply the event durably before ACK and then stop accepting the prior verifier. A separate pending-verifier column is not yet implemented.
