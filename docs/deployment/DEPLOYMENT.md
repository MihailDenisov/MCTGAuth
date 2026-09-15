# VPS deployment runbook

This runbook describes the public scripts in `deploy/vps/`. They target a Debian VPS with Java 21, SQLite, jq, curl, tar, util-linux/flock, and systemd. Run state-changing commands as `root` (normally through `sudo`). The scripts do not need Git or Docker.

## Layout and service

`install.sh` creates or preserves this layout:

```text
/opt/minecraft-auth/current -> /opt/minecraft-auth/releases/<release>
/opt/minecraft-auth/releases/       # application releases
/opt/minecraft-auth/shared/
/opt/minecraft-auth-tools/          # installed scripts
/var/lib/minecraft-auth/            # database
/var/backups/minecraft-auth/        # SQLite backups
/etc/minecraft-auth.env             # root-only configuration
/etc/systemd/system/minecraft-auth.service
/var/log/minecraft-auth-deploy.log
```

The service runs as `minecraft-auth:minecraft-auth`, from `/var/lib/minecraft-auth`, with `NoNewPrivileges`, `PrivateTmp`, `ProtectHome`, `ProtectSystem=strict`, `UMask=0027`, and write access only to `/var/lib/minecraft-auth`. The application is started from `current/bin/auth-backend` and is restarted by systemd after failure.

`AUTH_DATABASE_PATH` must be absolute and resolve below `/var/lib/minecraft-auth`. `HTTP_BIND_ADDRESS` must be `127.0.0.1`, `localhost`, or `::1`; `HTTP_PORT` must be 1–65535. Health polling uses `/health`, a five-second curl timeout, and 30 one-second attempts. Override `HEALTH_TIMEOUT` or `HEALTH_RETRIES` in the environment only when operationally necessary.

## Build and first installation

On a trusted build machine run `./gradlew :auth-backend:test` and `./gradlew :auth-backend:deploymentArchive` (or the Windows wrapper). The archive is `auth-backend/build/distributions/auth-backend-<version>.tar.gz`; it contains root-level `bin/`, `lib/`, and `release.properties`. An `installDist` directory with the same root layout is also accepted.

```bash
cd /home/authbot/uploads/vps-tools
chmod 0755 ./*.sh
sudo ./install.sh [installDist-directory-or-tar.gz]
```

`install.sh` requires root and Java 21. It creates the service account, directories, management-tool copies, unit file, a mode-0600 placeholder environment file if absent, and an empty SQLite database if absent; it reloads systemd and enables (but does not start) the service. An optional package is passed to `update.sh`. More than one argument is rejected. Existing environment, releases, database, and backups are preserved.

Replace every placeholder in `/etc/minecraft-auth.env` before starting the service. The file is never auto-augmented on later toolkit updates.

## Public commands

All paths below may be replaced with the corresponding path to a copied script. Unless stated otherwise, invalid arguments or failed external commands terminate with a nonzero status (`set -Eeuo pipefail`).

| Script and syntax | Privilege | Effects and failure behavior |
| --- | --- | --- |
| `update.sh <installDist-directory-or-tar.gz>` | root, lock | Validates regular files/directories, archive names, required files, metadata, and schema range; makes a SQLite backup; installs an immutable release; atomically switches `current`; stops/starts the service and checks health. A failed activation switches back and checks the old release; the failed release remains for diagnosis. |
| `backup.sh [--quiet]` | root | Makes a SQLite `.backup`, sets `0640 root:root`, checks `PRAGMA integrity_check`, and prints the path. `--quiet` prints only the path. Existing files receive a unique suffix. |
| `restore.sh [--yes] <backup.db>` | root, lock | Accepts only a regular, validated backup in the backup directory and an integrity-valid DB. Confirmation requires exactly `yes` unless `--yes`; creates an emergency backup, restores to a temporary DB, stops service, removes WAL/SHM, replaces the DB, and restarts/checks health. On failed health the restored DB remains and the emergency path is reported. |
| `rollback.sh [--yes] [--force-incompatible] [release-name]` | root, lock | Selects the previous release by default or a named release, validates it, and refuses if the DB schema exceeds its `max.schema.version` unless forced. Switches code only. Failed health restores the original release; if that also fails, it exits with both releases unhealthy. |
| `cleanup.sh [--dry-run]` | root, lock | Defaults to `KEEP_RELEASES=5` and `KEEP_BACKUPS=10`. Keeps at least two releases, protects active/previous releases, and removes only validated old targets. Dry-run reports removals without changing files. |
| `reset-database.sh [--dry-run] [--yes]` | root, lock | Destructive full authentication reset. Stops backend/Minecraft, backs up both stores, empties runtime tables transactionally, clears the server verifier cache/sync history, then restarts services that were active. |
| `start.sh` | root | Runs `systemctl start minecraft-auth.service`. No health wait is performed. |
| `stop.sh` | root | Runs `systemctl stop minecraft-auth.service`. |
| `restart.sh` | root | Restarts the service and waits for health. A restart can succeed while health fails; in that case it exits nonzero. |
| `health.sh` | any user with config/API access | Checks the configured loopback `/health`; prints pass/fail and returns nonzero on failure. |
| `status.sh` | usually `sudo` | Reports systemd state, current release/application version, DB/schema, health, uptime, disk usage, and backup count. |
| `versions.sh` | usually `sudo` | Lists current/previous release, application version, DB schema, and all release directories. |
| `logs.sh [-f] [-n <1..100000>]` | journal access | Shows 100 service lines by default. `-n` changes the count; `-f` follows. Unknown options and invalid counts fail. |

The shared `common.sh` is not an operator command. The nested `tests/safety_test.sh` is a helper test and is not copied by `install.sh`.

## Destructive full authentication reset

Set `MINECRAFT_SERVICE_NAME`, `MINECRAFT_SERVICE_USER`, `MINECRAFT_SERVICE_GROUP`, and absolute `MINECRAFT_GAME_DIR` in `/etc/minecraft-auth.env`. Preview counts first:

```bash
sudo /opt/minecraft-auth-tools/reset-database.sh --dry-run
sudo /opt/minecraft-auth-tools/reset-database.sh
# automation only, after separately confirming the target:
sudo /opt/minecraft-auth-tools/reset-database.sh --yes
```

Without `--yes`, the exact phrase `RESET ALL AUTH DATA` is required. The command preserves the schema, `schema_migrations`, `moderators`, `moderator_permissions`, and SQLite ID sequences. It deletes users, all ticket/type/message/attachment/decision data, accounts/nicknames/verifiers/password-change history, pending moderator input, sync events, remote commands, and audit history. Preserving ID sequences prevents old Telegram callbacks from colliding with future records; callbacks for deleted records become localized stale actions.

Before mutation it stops Minecraft and the backend, creates a consistent SQLite backup and a `mctgauth-cache-*.tar.gz` backup under `/var/backups/minecraft-auth`, and sets both to `0600 root:root`. It then replaces `<MINECRAFT_GAME_DIR>/mctgauth/accounts.json` with an empty schema-valid store and removes its `.bak`/`.tmp`, clearing cached verifiers, processed event IDs, and command IDs. Players online at the time are disconnected by normal Minecraft service shutdown. There is no window in which an empty backend runs beside the old authentication cache.

After a full reset, every previously registered user must register again. All previously issued Minecraft authentication passwords become invalid. Client installations may still display an obsolete password in `config/mctgauth-client.json`; it no longer authenticates and should be replaced after registration.

For coordinated restore, stop both services, restore the reported `auth-backend-*.db` with `restore.sh`, stop the backend again after its health check, extract the matching cache archive into `<MINECRAFT_GAME_DIR>/mctgauth`, restore Minecraft service ownership/mode, then start backend and Minecraft. Never restore only one side. For intentional backend-only recovery, leave Minecraft stopped and perform a fresh full resynchronization before accepting players.

## Update transaction and compatibility

```text
validate package and metadata/schema range
 -> SQLite backup
 -> copy and validate release
 -> stop service
 -> atomic current-link switch
 -> start and poll /health
```

`release.properties` must include `application.version`, `min.schema.version`, and `max.schema.version` for an update. The database schema must be within that range. Rollback checks only the target maximum and never rewinds the database. Keep the failed release until its logs have been reviewed; `cleanup.sh` may remove it later.

## Routine operations

```bash
sudo /opt/minecraft-auth-tools/status.sh
sudo /opt/minecraft-auth-tools/health.sh
sudo /opt/minecraft-auth-tools/logs.sh -n 200
sudo /opt/minecraft-auth-tools/logs.sh -f
sudo /opt/minecraft-auth-tools/versions.sh
sudo /opt/minecraft-auth-tools/restart.sh
sudo /opt/minecraft-auth-tools/backup.sh
sudo /opt/minecraft-auth-tools/cleanup.sh --dry-run
sudo /opt/minecraft-auth-tools/cleanup.sh
```

Do not put secrets in an archive or manually unpack an upload as root. `update.sh` performs the archive safety checks. Update, rollback, restore, and cleanup share one lock and cannot run concurrently. State-changing operations record UTC operation, releases, result, and sanitized operator in `/var/log/minecraft-auth-deploy.log`; secrets are not logged.
