# Upgrade from MCTGAuth 0.4.x to 0.5.0

[Русская версия](MIGRATION_0.4.x_TO_0.5.0_RU.md)

## Overview

MCTGAuth 0.5.0 is an in-place application and mod upgrade from 0.4.1 or 0.4.2. Authentication protocol version stays at `1`, backend database schema stays at `4`, and the Minecraft cache format stays at schema `1`. Existing synchronized accounts therefore need no credential reset and remain usable during a backend outage.

Plan a short backend restart and a normal Minecraft server restart to replace the server JAR. Players must restart their clients after replacing the client JAR. Updating all three components together is recommended even though the wire protocol has not changed.

## 1. Back up before upgrading

On the VPS, create and record a verified SQLite backup:

```bash
sudo /opt/minecraft-auth-tools/backup.sh
sudo cp -a /etc/minecraft-auth.env /etc/minecraft-auth.env.pre-0.5.0
sudo cp -a /etc/minecraft-auth-telegram.yaml /etc/minecraft-auth-telegram.yaml.pre-0.5.0
```

The database backup is written under `/var/backups/minecraft-auth/auth-backend-<UTC timestamp>.db`. Do not copy a live SQLite database directly.

Before replacing the server mod, stop the configured Minecraft service and back up its authentication cache. Substitute the absolute `MINECRAFT_GAME_DIR` from `/etc/minecraft-auth.env`:

```bash
sudo systemctl stop minecraft.service
sudo tar -C <MINECRAFT_GAME_DIR> -czf /var/backups/minecraft-auth/mctgauth-cache-pre-0.5.0.tar.gz mctgauth
```

If `MINECRAFT_SERVICE_NAME` is not `minecraft.service`, use its configured value.

## 2. Add the Telegram feature configuration

The installer creates `/etc/minecraft-auth-telegram.yaml` only when absent and adds:

```text
TELEGRAM_FEATURE_CONFIG_PATH=/etc/minecraft-auth-telegram.yaml
```

For an existing installation, copy `deploy/vps/telegram-features.example.yaml`, set ownership to `root:minecraft-auth` and mode `0640`, then add the environment setting without replacing existing secrets.

Supported YAML keys are:

```yaml
howToPlay:
  en: |
    English instructions.
  ru: |
    Инструкция на русском языке.
registration:
  subscriptionCheck:
    enabled: false
    channel: "@example_channel"
    channelUrl: "https://t.me/example_channel"
serverMap:
  enabled: false
  url: "https://map.example.com/"
modpackDownload:
  enabled: false
  url: "https://t.me/example_channel/123"
```

All optional features default to disabled. Invalid optional URLs are hidden with a warning, and built-in How to Play text remains the fallback. Configuration is loaded only at backend startup.

If `registration.subscriptionCheck.enabled` is `true`, add the bot as an administrator of the configured channel so Telegram reliably permits `getChatMember` checks for other users. Confirm that `channel` names the same channel as `channelUrl`. A Telegram API failure fails closed for new registration and offers a retry; it does not classify the user as a non-member.

## 3. Install 0.5.0

Upload `auth-backend-0.5.0.tar.gz`, verify it against `SHA256SUMS.txt`, and run:

```bash
sudo /opt/minecraft-auth-tools/update.sh /path/to/auth-backend-0.5.0.tar.gz
sudo /opt/minecraft-auth-tools/status.sh
```

`update.sh` creates its own SQLite backup, installs an immutable release directory, switches the current symlink atomically, and automatically restores the prior application release if the health check fails. No new SQL migration runs for 0.5.0: schema `4` remains schema `4`.

Replace the old server mod with `mctgauth-server-0.5.0.jar`, keep `config/mctgauth-server.toml`, and verify:

```toml
[sync]
disconnectPlayerWhenAccountDisabled = true
```

Start the Minecraft service, then distribute `mctgauth-client-0.5.0.jar` to players. Do not install the server JAR on clients or the client JAR on the dedicated server.

## 4. Verify the upgrade

- `/health` reports `"status":"ok"` and schema version `4`.
- `/version` reports application version `0.5.0` and the expected release commit.
- `journalctl -u minecraft-auth.service` contains no feature-config or Telegram authorization errors.
- `/menu` shows only configured links and localized How to Play content.
- A test registration passes the subscription gate only after membership is visible to the bot.
- Disabling a test account produces an audited state change, synchronizes it, and disconnects an online player when the server setting above is enabled.
- Previously synchronized active accounts can still authenticate if the backend is temporarily stopped.

## Rollback

Because schema and cache formats are unchanged, application rollback to 0.4.2 is supported:

```bash
sudo /opt/minecraft-auth-tools/versions.sh
sudo /opt/minecraft-auth-tools/rollback.sh <0.4.2-release-directory>
```

Restore the previous client and server JARs together. Application rollback does not restore the database. Restore the database and matching Minecraft cache only for data recovery, with both services stopped; never restore only one side.

## Full reset is not an upgrade step

`sudo /opt/minecraft-auth-tools/reset-database.sh` is an optional destructive operation. It deletes users, registrations, accounts, verifiers, tickets, messages, sync events, remote commands, and audit history, and clears the Minecraft authentication cache. All players must register again. Use `--dry-run` first and follow the exact confirmation procedure only when a complete reset is intentionally required.
