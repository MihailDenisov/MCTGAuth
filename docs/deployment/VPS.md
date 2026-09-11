# VPS Deployment Notes

Target: small Debian VPS, Java 21 runtime, systemd, SQLite, optional Caddy reverse proxy, backend bound to localhost behind HTTPS reverse proxy. Telegram long polling needs outbound HTTPS and avoids webhook exposure.

Production secrets belong in a root-owned environment file (for example `/etc/minecraft-auth.env`) with restrictive permissions. Keep application data under a dedicated directory and cap journal/log growth on small disks.

Required Phase 2 values are `TELEGRAM_BOT_TOKEN`, `TELEGRAM_ADMIN_CHAT_ID`, `MODERATOR_TELEGRAM_IDS`, `HOW_TO_PLAY_URL`, `AUTH_API_TOKEN`, and `SERVER_ID`. `AUTH_DATABASE_PATH`, `HTTP_BIND_ADDRESS`, and `HTTP_PORT` have defaults. The API token must contain at least 32 characters.

The application rejects non-loopback `HTTP_BIND_ADDRESS` values. Configure Caddy to terminate public HTTPS and proxy only the authenticated Minecraft API routes to `127.0.0.1:8080`. Do not expose port 8080 directly.

Release-based installation, SFTP updates, backup/restore, rollback, health checking, and cleanup are documented in [VPS_MANAGEMENT.md](VPS_MANAGEMENT.md). The repository scripts are under `deploy/vps/`, with a Russian quick-start guide in [`deploy/vps/README.md`](../../deploy/vps/README.md).
