# VPS Management Toolkit

The maintained Debian production scripts live in `deploy/vps/`. Running `install.sh` copies them to `/opt/minecraft-auth-tools`, installs `minecraft-auth.service`, and prepares release, data, and backup directories. The quick operator guide in Russian is [`deploy/vps/README.md`](../../deploy/vps/README.md).

## Production layout

```text
/opt/minecraft-auth/current -> /opt/minecraft-auth/releases/<release>
/opt/minecraft-auth/releases/
/opt/minecraft-auth/shared/
/opt/minecraft-auth-tools/
/var/lib/minecraft-auth/auth-backend.db
/var/backups/minecraft-auth/
/etc/minecraft-auth.env
/var/log/minecraft-auth-deploy.log
```

Releases contain application files only. SQLite data, environment secrets, logs, and backups remain outside the release tree.

## VPS prerequisites

The host needs Java 21, `sqlite3`, `curl`, `tar`, `flock`, and systemd. On a Debian release whose configured repositories provide OpenJDK 21, install them with:

```bash
sudo apt update
sudo apt install openjdk-21-jre-headless sqlite3 curl tar util-linux
java -version
```

If OpenJDK 21 is not available from the configured repositories, install another maintained Java 21 runtime and verify that the system `java` command resolves to major version 21 before running `install.sh`.

## Developer and SFTP workflow

Build and test locally:

```bash
./gradlew :auth-backend:test
./gradlew :auth-backend:deploymentArchive
```

Upload `auth-backend/build/distributions/auth-backend-<version>.tar.gz` through SFTP to `/home/authbot/uploads/`. The production VPS does not need repository or Git credentials.

Initial toolkit installation:

```bash
cd /home/authbot/uploads/vps-tools
chmod 0755 ./*.sh
sudo ./install.sh
sudoedit /etc/minecraft-auth.env
sudo chmod 0600 /etc/minecraft-auth.env
```

Activate an uploaded package:

```bash
sudo /opt/minecraft-auth-tools/update.sh \
  /home/authbot/uploads/auth-backend-0.2.tar.gz
```

`update.sh` accepts either the generated `.tar.gz` or an `installDist` directory whose root contains `bin/`, `lib/`, and `release.properties`. It never executes installer code from the upload.

## Update transaction

The management lock at `/run/lock/minecraft-auth-update.lock` serializes update, rollback, restore, and cleanup operations.

Update order:

```text
validate package and schema range
    -> safe SQLite backup
    -> copy into a new release directory
    -> validate release
    -> stop service
    -> atomically switch current
    -> start service
    -> poll local /health
```

Failure before symlink switching leaves the active release untouched. Failure after switching restores the previous symlink, restarts the previous release, and checks its health. The failed release is retained for diagnosis; cleanup may remove it later.

## Operations

```bash
sudo /opt/minecraft-auth-tools/status.sh
sudo /opt/minecraft-auth-tools/health.sh
sudo /opt/minecraft-auth-tools/logs.sh -n 200
sudo /opt/minecraft-auth-tools/logs.sh -f
sudo /opt/minecraft-auth-tools/restart.sh
sudo /opt/minecraft-auth-tools/versions.sh
sudo /opt/minecraft-auth-tools/backup.sh
sudo /opt/minecraft-auth-tools/cleanup.sh --dry-run
sudo /opt/minecraft-auth-tools/cleanup.sh
```

Application rollback:

```bash
sudo /opt/minecraft-auth-tools/rollback.sh
sudo /opt/minecraft-auth-tools/rollback.sh <release-name>
```

Database restore:

```bash
sudo /opt/minecraft-auth-tools/restore.sh <backup.db>
sudo /opt/minecraft-auth-tools/restore.sh --yes <backup.db>
```

Application rollback and database restoration are deliberately separate. A newer application can apply a schema that an older release cannot read. `rollback.sh` compares the current schema with the target release's `max.schema.version` and refuses an incompatible switch unless the operator explicitly supplies `--force-incompatible` after reviewing migrations.

## Backup and retention

Backups use SQLite `.backup`, followed by `PRAGMA integrity_check`; raw WAL-mode database files are not copied. The default retention is `KEEP_RELEASES=5` and `KEEP_BACKUPS=10`. `cleanup.sh` protects the active and immediately previous release and validates every destructive target.

## Health and public metadata

`health.sh` reads only `HTTP_BIND_ADDRESS` and `HTTP_PORT` without sourcing `/etc/minecraft-auth.env`, then checks the loopback `/health` response with a timeout. `/health` exposes safe version, commit, and schema fields. `GET /version` exposes:

```json
{
  "application": "minecraft-auth-backend",
  "version": "0.2",
  "commit": "unknown",
  "buildTime": "2026-09-11T17:05:00Z",
  "schemaVersion": 3
}
```

Set `GIT_COMMIT` in the trusted build environment if commit metadata is available. Neither endpoint exposes tokens, database paths, Telegram IDs, or environment contents.

## Deployment log

State-changing operations append UTC timestamp, operation, old/new release, result, and operator name to `/var/log/minecraft-auth-deploy.log`. Secrets and environment-file contents are never logged.
