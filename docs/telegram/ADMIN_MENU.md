# Telegram Administration Menu

Enabled moderators receive the administration entry in their stored private-chat locale. Every action re-checks the actor's numeric Telegram ID and backend permission; localized button visibility is not authorization.

`/adm` opens this menu in private chat and the inline shared panel in the configured admin group. In private chat, `/menu` always opens the ordinary user-facing main menu, including for moderators. The `Main menu` / `Главное меню` action has the same behavior; `Admin menu` / `Меню администратора` is the separate administrative home action. Both `/command` and `/command@ConfiguredBotUsername` forms are supported; commands addressed to another bot are ignored.

English private menu:

```text
🛡 Administration

📨 Requests                    📊 Player Statistics
➕ Register Minecraft account
⚙️ Server management
```

Russian private menu:

```text
🛡 Администрирование

📨 Обращения                   📊 Статистика игроков
➕ Зарегистрировать игровой аккаунт
⚙️ Управление сервером
```

## Requests submenu

All ticket/request views are grouped under **📨 Requests** / **📨 Обращения**:

```text
EN: 🆕 New tickets | 👤 My tickets | 🟡 In progress | 📝 Registrations
    ⚠️ Violations | 🛠 Problems | 📊 Statistics | 🔎 Search | ⬅️ Back
RU: 🆕 Новые обращения | 👤 Мои обращения | 🟡 В работе | 📝 Регистрации
    ⚠️ Нарушения | 🛠 Проблемы | 📊 Статистика | 🔎 Поиск | ⬅️ Назад
```

Assignment, replies, decisions, paging, search, and ticket statistics remain available there. **📊 Player Statistics** is a separate permission-controlled, paginated view (ten accounts per page, ordered by recent activity).

The shared administration group uses `ADMIN_LOCALE`, independently of the ticket creator and acting moderator. For example, `ADMIN_LOCALE=ru` renders Russian cards even when the creator uses English. Moderator prompts sent to private chat use that moderator's persisted locale.

Ticket actions use stable callbacks such as `ticket:1042:take`, `ticket:1042:reply`, and `ticket:1042:approve`. Telegram cards remain projections; SQLite is authoritative. Search accepts ticket number, Minecraft nickname, Telegram numeric ID, or username snapshot.

Resolve and Close do not transition immediately. The bot asks whether to add a public closing comment and offers Add Comment, Close Without Comment, and Cancel. Comment entry is private, supports `/cancel`, and is limited to 4,000 characters. A concurrent completed action produces an already-closed response without adding another history or audit transition.

Search also returns matching Minecraft accounts. A private account card shows the owner identity, enabled state, credential version, and registration time. `admin-account:<id>:disable` starts an optional-reason step and requires a separate confirmation callback before the backend atomically changes `enabled`, emits `ACCOUNT_DISABLED`, and writes the audit entry. `admin-account:<id>:enable` restores access and emits `ACCOUNT_ENABLED`. Repeating the current state is idempotent and creates no redundant transition.

Audit details include the moderator numeric Telegram ID, target account/nickname, explicit previous/new enabled values, optional internal reason, and UTC timestamp. One owner may have multiple accounts; every callback includes the selected account ID and changes only that record.

## Telegram bot blocking

Account administration exposes **🚫 Block User in Bot** / **✅ Unblock User in Bot** with confirmation. The target is identified by numeric Telegram ID; actual transitions are audited with moderator ID, previous/new state, optional reason, and timestamp. Repeating an already-applied state is idempotent.

When `users.bot_blocked=1`, the user cannot register, create tickets or reports, add accounts, or start password workflows. `/start` and `/menu` return the localized restricted-access response rather than granting workflow access. This state is independent from a Minecraft account's `enabled` flag.

The shared group uses `ADMIN_LOCALE`; private prompts use the moderator's stored locale. Reply composition, search input, and manual registration occur privately. A group Reply callback persists a moderator-specific action with a 30-minute expiry, and the same assigned moderator completes it by sending private text.

Server management menus:

When Telegram member tags are enabled, Server management includes **Synchronize Chat Tags**. It requires `telegram.member_tags.sync`, reconciles only the explicit configured target chat, and reports processed, updated, cleared, skipped-administrator, non-member, and failed counts without exposing API credentials. Telegram administrators/owners are skipped regardless of MCTGAuth moderator status.

```text
EN: 🔄 Force authentication sync | 📊 Authentication status | 🔁 Resync player
RU: 🔄 Принудительная синхронизация | 📊 Состояние авторизации | 🔁 Повторно синхронизировать игрока
```

The underlying command values remain `FORCE_AUTH_SYNC`, `AUTH_STATUS`, and `RESYNC_PLAYER`. Commands are typed, targeted, permission-checked, durable, audited, and expiring. No raw shell, RCON, or Minecraft command field exists.

All Telegram-visible timestamps are formatted centrally in `Europe/Moscow` (`dd.MM.yyyy HH:mm МСК` in Russian and `dd.MM.yyyy HH:mm MSK` in English). Database timestamps and all TTL calculations remain UTC `Instant` values.
