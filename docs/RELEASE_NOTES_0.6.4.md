# MCTGAuth 0.6.4

[Russian version](RELEASE_NOTES_0.6.4_RU.md)

## Telegram member tags

- **Settings → Chat Tag** lets a user explicitly select one enabled Minecraft profile they own, or **No tag**. No tag is the default; the backend never chooses a profile automatically.
- The preference stores `users.chat_tag_account_id`, not free text. Every callback revalidates ownership and enabled state, and the displayed tag is resolved from the authoritative account nickname.
- Tags apply only to the explicit `memberTags.chatId`. Telegram usernames, private chats, the moderator group, and arbitrary group activity never choose the destination.
- Telegram chat administrators and owners are not assigned game tags. MCTGAuth moderator status is independent: a moderator who is an ordinary member of the target chat may select a tag.
- A non-member may save a preference. Temporary Telegram API failures also leave the committed preference intact. The bot retries when it receives a target-chat membership update, and an authorized administrator can run **Synchronize Chat Tags** to reconcile all saved preferences.
- Reconciliation returns `processed`, `updated`, `cleared`, `skippedAdministrators`, `notMembers`, and `failed` counts. Invalid selections are cleared instead of being silently changed to another account.

Member tags require Telegram Bot API 9.5 or later. The bot must be an administrator in the target group/supergroup and have `can_manage_tags`. Configure one numeric target chat ID:

```yaml
memberTags:
  enabled: true
  chatId: <TELEGRAM_GROUP_CHAT_ID>
  excludeChatAdministrators: true
```

`chatId` is mandatory when enabled. Invalid configuration disables the feature with an actionable startup warning. Version 0.6.4 always excludes Telegram administrators/owners; keep `excludeChatAdministrators: true`.

## Registration rejection reasons

- Rejecting a registration now offers **Add Reason**, **Reject Without Reason**, and **Cancel**.
- A reason is entered in the moderator's private chat, is limited to 1,000 characters, and is persisted as the registration decision comment in the same transaction as the `REJECTED` transition.
- The applicant receives the localized reason. Telegram sends it as plain text, so moderator text is not interpreted as Markdown or HTML.
- The audit record stores moderator numeric ID, ticket ID, transition time, and whether a comment is present; it does not duplicate the full comment.
- Compare-and-set decision semantics ensure only the first valid moderator action succeeds. A stale action cannot append a reason after finalization.

## Precise authentication failures

Authentication still uses wire protocol `1`, but client/server 0.6.4 provide reason-specific English and Russian disconnect messages:

- `ACCOUNT_NOT_FOUND`: the connected nickname is absent from the synchronized local cache, including visually similar but different names.
- `ACCOUNT_NOT_SYNCHRONIZED`: reserved for a real synchronization-state gap.
- `ACCOUNT_DISABLED`: the profile is blocked.
- `INVALID_CREDENTIALS`: the proof does not match; no verifier/HMAC detail is exposed.
- `CREDENTIAL_OUTDATED`: the reliably detected credential version differs after a password change.
- `AUTH_TIMEOUT`: authentication did not finish before the deadline.
- `INVALID_CHALLENGE`, `REPLAY_DETECTED`, and `INVALID_SESSION`: one safe generic verification message.
- `PROTOCOL_MISMATCH`: client and server versions are incompatible.
- `INTERNAL_AUTH_ERROR`: a safe generic internal-error message without stack traces, database details, URLs, tokens, verifiers, or proof material.

## `RESYNC_PLAYER` diagnostics

The typed version-1 command now returns structured failures: `ACCOUNT_NOT_FOUND`, `MINECRAFT_NAME_MISMATCH`, `BACKEND_UNAVAILABLE`, `SYNC_EVENT_NOT_FOUND`, `ACCOUNT_DISABLED`, or `INTERNAL_ERROR` where applicable. A connected-name mismatch is detected by resolving the player with the cached account UUID and safely returns `accountId`, `registeredMinecraftName`, `connectedMinecraftName`, and `reason=MINECRAFT_NAME_MISMATCH`. Credentials and verifier data are never returned.

## Compatibility

| Item | 0.6.4 requirement |
| --- | --- |
| Database schema | `7`; automatic migration required |
| Authentication wire protocol | `1`; unchanged |
| Backend update | Required |
| Dedicated-server update | Required for new failure classification and `RESYNC_PLAYER` diagnostics |
| Client update | Required for all new localized disconnect messages |

Deploy the backend first, then replace both Minecraft mod JARs before reopening the server. Existing synchronized accounts remain login-capable during a temporary backend outage, but mixing 0.6.3 and 0.6.4 does not provide the complete 0.6.4 behavior.

See [the 0.6.3 to 0.6.4 upgrade guide](MIGRATION_0.6.3_TO_0.6.4.md).
