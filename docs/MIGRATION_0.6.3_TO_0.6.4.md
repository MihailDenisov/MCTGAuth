# Upgrade from MCTGAuth 0.6.3 to 0.6.4

[Russian version](MIGRATION_0.6.3_TO_0.6.4_RU.md)

Version 0.6.4 requires a coordinated backend, dedicated-server, and client update. Authentication wire protocol remains `1`, but the backend database migrates from schema `6` to `7`, the server classifies additional failure reasons and returns structured `RESYNC_PLAYER` diagnostics, and the client supplies their localized messages.

## 1. Prepare and back up

Stop new administrative work, record the current release, and create a verified SQLite backup:

```bash
sudo /opt/minecraft-auth-tools/status.sh
sudo /opt/minecraft-auth-tools/backup.sh
```

Also preserve `/etc/minecraft-auth.env` and `/etc/minecraft-auth-telegram.yaml`. Never place their secrets in the release archive.

## 2. Configure optional member tags

Member tags are disabled by default. To enable them, edit the existing file referenced by `TELEGRAM_FEATURE_CONFIG_PATH` (normally `/etc/minecraft-auth-telegram.yaml`) and add:

```yaml
memberTags:
  enabled: true
  chatId: <TELEGRAM_GROUP_CHAT_ID>
  excludeChatAdministrators: true
```

Use the numeric ID of exactly one target group or supergroup. Do not use the moderator/admin chat implicitly unless it is deliberately the same explicitly configured target. The bot must use Telegram Bot API 9.5 or later, be an administrator in that chat, and have `can_manage_tags`.

Version 0.6.4 never assigns a game tag to a Telegram chat administrator or owner; keep `excludeChatAdministrators: true`. MCTGAuth moderator permissions are separate from the user's Telegram chat role.

If `enabled: true` has no valid `chatId`, startup safely disables member tags and logs a non-secret warning. Users default to **No tag**. Saving a preference does not require current membership and is not rolled back when Telegram is unavailable. Join updates and the permission-controlled **Synchronize Chat Tags** admin action provide reconciliation.

## 3. Update the backend

Upload and activate the 0.6.4 backend archive through the normal managed updater:

```bash
sudo /opt/minecraft-auth-tools/update.sh \
  /home/authbot/uploads/auth-backend-0.6.4.tar.gz
```

Startup applies `V007__chat_tags_and_registration_rejection.sql`. It adds nullable `users.chat_tag_account_id` referencing `minecraft_accounts(id)` with `ON DELETE SET NULL` and extends persisted moderator pending actions for registration rejection reasons. Existing users remain at No tag.

Verify:

```bash
sudo /opt/minecraft-auth-tools/health.sh
sudo /opt/minecraft-auth-tools/logs.sh -n 200
sqlite3 /var/lib/minecraft-auth/auth-backend.db \
  "SELECT version, name FROM schema_migrations ORDER BY version;"
```

Expected `/version` values are application `0.6.4` and schema `7`. Logs must not contain bot/API tokens, authorization headers, passwords, verifiers, or proof material.

Application-only rollback to a release whose maximum schema is below 7 is blocked by the deployment tooling. Restore a matched pre-upgrade database backup only through the confirmed restore procedure when an actual data rollback is required.

## 4. Update Minecraft server and clients

Stop Minecraft, replace the server JAR with `mctgauth-server-0.6.4.jar`, and start it after the backend health check succeeds. Distribute `mctgauth-client-0.6.4.jar` to every player before reopening the server.

Protocol version stays `1`; no credential reset or account-cache reset is required. Both mod updates are nevertheless required for complete reason-specific disconnect messaging. The server update is also required for UUID-based `MINECRAFT_NAME_MISMATCH` results from `RESYNC_PLAYER`.

## 5. Verify behavior

- In a private bot chat, open **Settings → Chat Tag**. Confirm only the user's enabled profiles plus **No tag** appear.
- Save No tag and a profile selection. Confirm both succeed even if the user is temporarily outside the target chat.
- Confirm an ordinary target-chat member receives the selected authoritative nickname; confirm an administrator/owner receives no game tag.
- Temporarily deny Telegram access or inspect a controlled failure: the database preference must remain saved. Restore access and run **Synchronize Chat Tags** with a moderator that has `telegram.member_tags.sync`.
- Reject one registration with a reason and one without. Confirm the applicant sees the reason only for the first, and a stale second moderator action is rejected.
- Verify unknown nickname, disabled account, wrong password, changed-password version, timeout, protocol mismatch, and safe verification/internal failures display the appropriate localized message.
- Run `RESYNC_PLAYER` for a known account. If the currently connected UUID resolves to another name, confirm the structured result includes only the account ID, registered name, connected name, and `MINECRAFT_NAME_MISMATCH` reason.

Do not reset player sessions or authentication data as part of this migration.
