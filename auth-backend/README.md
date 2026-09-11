# Authentication Backend Installation

[Русская версия](README_RU.md)

The backend is a standalone Java 21 application. It runs the Telegram bot through long polling and stores authoritative registration, moderation, audit, and synchronization data in SQLite.

The backend includes button-oriented Telegram user/admin workflows, ordered SQLite migrations, a loopback HTTP health/synchronization API, and the durable typed remote-command queue. The Minecraft/NeoForge consumer is implemented separately.

## Requirements

- 64-bit JDK or JRE 21
- Outbound HTTPS access to `api.telegram.org`
- A Telegram bot created through BotFather
- A private Telegram administration group
- The repository Gradle Wrapper; no global Gradle installation is required

Verify Java before continuing:

```text
java -version
```

The reported major version must be 21.

## 1. Configure Telegram

1. Create a bot with BotFather and keep its token private.
2. Add the bot to the private administration group.
3. Send a command mentioning the bot in that group, such as `/start@your_bot_name`.
4. Obtain the group's numeric chat ID from a `getUpdates` response. Telegram supergroup IDs are normally negative.
5. Obtain each moderator's immutable numeric Telegram user ID. Do not use usernames for authorization.

For example, after putting the token in an environment variable, PowerShell can display recent updates without placing the literal token in the command:

```powershell
$env:TELEGRAM_BOT_TOKEN = '<bot-token>'
(Invoke-RestMethod "https://api.telegram.org/bot$env:TELEGRAM_BOT_TOKEN/getUpdates").result | ConvertTo-Json -Depth 10
```

Look for `message.chat.id` for the administration group and `message.from.id` for a moderator. Do not paste the response into a public issue because it contains Telegram identifiers and message content.

## 2. Configure environment variables

The application reads its configuration directly from process environment variables. It does not automatically load `.env` files.

| Variable | Required | Description |
| --- | --- | --- |
| `TELEGRAM_BOT_TOKEN` | Yes | Secret token issued by BotFather. |
| `TELEGRAM_ADMIN_CHAT_ID` | Yes | Numeric ID of the private administration group. |
| `MODERATOR_TELEGRAM_IDS` | Yes | Comma-separated numeric Telegram user IDs authorized to decide tickets. |
| `AUTH_DATABASE_PATH` | No | SQLite path; defaults to `./data/auth-backend.db`. |
| `HOW_TO_PLAY_URL` | Yes | Absolute HTTPS URL for the detailed server guide. |
| `HTTP_BIND_ADDRESS` | No | Loopback bind address; defaults to `127.0.0.1`. Non-loopback values are rejected. |
| `HTTP_PORT` | No | Local HTTP port; defaults to `8080`. |
| `AUTH_API_TOKEN` | Yes | Random server API secret containing at least 32 characters. |
| `SERVER_ID` | Yes | Stable server target such as `survival-main`. |
| `ADMIN_LOCALE` | No | Shared administration-group language: `en` or `ru`; defaults to `en`. |
| `DEFAULT_USER_LOCALE` | No | Initial user language when Telegram provides no supported suggestion; `en` or `ru`, default `en`. |

Never commit real values. Ensure the account running the service can write to the database directory.

## 3. Build and test

Run these commands from the repository root.

Windows PowerShell:

```powershell
.\gradlew.bat :auth-backend:test
.\gradlew.bat :auth-backend:installDist
```

Linux:

```bash
./gradlew :auth-backend:test
./gradlew :auth-backend:installDist
```

The runnable distribution is created under `auth-backend/build/install/auth-backend/`.

## 4. Run locally on Windows

From the repository root:

```powershell
$env:TELEGRAM_BOT_TOKEN = '<bot-token>'
$env:TELEGRAM_ADMIN_CHAT_ID = '-1000000000000'
$env:MODERATOR_TELEGRAM_IDS = '123456789,987654321'
$env:AUTH_DATABASE_PATH = '.\data\auth-backend.db'
$env:HOW_TO_PLAY_URL = 'https://t.me/example/1'
$env:HTTP_BIND_ADDRESS = '127.0.0.1'
$env:HTTP_PORT = '8080'
$env:AUTH_API_TOKEN = '<at-least-32-random-characters>'
$env:SERVER_ID = 'survival-main'
$env:ADMIN_LOCALE = 'ru'
$env:DEFAULT_USER_LOCALE = 'en'
.\gradlew.bat :auth-backend:run
```

Alternatively, after `installDist`:

```powershell
.\auth-backend\build\install\auth-backend\bin\auth-backend.bat
```

Send `/start` to the bot in a private chat, select a language, and start registration from the localized menu. A completed questionnaire should create an admin-group ticket card rendered using `ADMIN_LOCALE`.

## 5. Install on a Debian VPS

Install a Java 21 runtime using the package source or JDK vendor appropriate for the VPS, then verify it with `java -version`. Build the distribution on a trusted machine or on the VPS and copy the contents of `auth-backend/build/install/auth-backend/` to `/opt/minecraft-auth/`.

Create a dedicated service account and data directory:

```bash
sudo useradd --system --home /var/lib/minecraft-auth --shell /usr/sbin/nologin minecraft-auth
sudo install -d -o minecraft-auth -g minecraft-auth -m 0750 /var/lib/minecraft-auth
sudo install -d -o root -g root -m 0755 /opt/minecraft-auth
```

Place the built distribution in `/opt/minecraft-auth/` and make its launcher executable:

```bash
sudo chmod 0755 /opt/minecraft-auth/bin/auth-backend
sudo chown -R root:root /opt/minecraft-auth
```

Create `/etc/minecraft-auth.env` as root:

```text
TELEGRAM_BOT_TOKEN=<bot-token>
TELEGRAM_ADMIN_CHAT_ID=-1000000000000
MODERATOR_TELEGRAM_IDS=123456789,987654321
AUTH_DATABASE_PATH=/var/lib/minecraft-auth/auth-backend.db
HOW_TO_PLAY_URL=https://t.me/example/1
HTTP_BIND_ADDRESS=127.0.0.1
HTTP_PORT=8080
AUTH_API_TOKEN=<at-least-32-random-characters>
SERVER_ID=survival-main
ADMIN_LOCALE=ru
DEFAULT_USER_LOCALE=en
```

Restrict access to the secrets file:

```bash
sudo chown root:root /etc/minecraft-auth.env
sudo chmod 0600 /etc/minecraft-auth.env
```

Create `/etc/systemd/system/minecraft-auth.service`:

```ini
[Unit]
Description=Minecraft Telegram Authentication Backend
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=minecraft-auth
Group=minecraft-auth
WorkingDirectory=/var/lib/minecraft-auth
EnvironmentFile=/etc/minecraft-auth.env
ExecStart=/opt/minecraft-auth/bin/auth-backend
Restart=on-failure
RestartSec=5s
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadWritePaths=/var/lib/minecraft-auth

[Install]
WantedBy=multi-user.target
```

Load and start the service:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now minecraft-auth.service
sudo systemctl status minecraft-auth.service
```

View sanitized application output with:

```bash
sudo journalctl -u minecraft-auth.service -f
```

The application deliberately avoids logging bot tokens and plaintext passwords. Still treat journal access as administrative access.

For release-based production updates, automatic health rollback, safe SQLite backup/restore, status, logs, and cleanup, see [`deploy/vps/README.md`](../deploy/vps/README.md) and [`docs/deployment/VPS_MANAGEMENT.md`](../docs/deployment/VPS_MANAGEMENT.md).

## Localization

The Telegram interface supports English and Russian. A new user receives an English/Russian language selector before registration. The choice is stored in SQLite and can later be changed through `⚙️ Settings`. Telegram's `language_code` is only an initial suggestion; it never overrides a stored choice.

`ADMIN_LOCALE` independently controls the shared administration group. A moderator's private chat uses that moderator's stored user locale. Telegram numeric IDs, callback identifiers, database values, and internal enums remain stable and language-independent.

Translations are stored in `src/main/resources/i18n/messages_en.properties` and `messages_ru.properties`. To add a language, add it to `BotLocale`, create the matching resource, translate every required `MessageKey`, extend locale validation and routing tests, and document the new supported code. English remains the runtime fallback. Translation-key parity is enforced by tests.

## Updating

1. Back up `/var/lib/minecraft-auth/auth-backend.db` while the service is stopped.
2. Build a new distribution with `:auth-backend:installDist`.
3. Replace `/opt/minecraft-auth/` with the new distribution.
4. Start the service and check its status and Telegram registration flow.

Do not delete the SQLite database during an update. It is the source of truth for tickets, accounts, audit records, and pending synchronization events.

## Troubleshooting

- `TELEGRAM_BOT_TOKEN is required`: define the variable in the same process environment or systemd environment file used to launch the backend.
- No admin card appears: verify the negative administration chat ID, bot membership, outbound HTTPS access, and service status.
- A moderator sees an authorization error: add their numeric `from.id` to `MODERATOR_TELEGRAM_IDS` and restart the service.
- SQLite cannot open the database: verify that the service account owns and can write to the parent directory.
- The bot does not react in a private chat: check `journalctl`, confirm the token belongs to the expected bot, and ensure another process is not polling the same bot token.
