# Telegram Administration Menu

Enabled moderators receive the administration entry in their stored private-chat locale. Every action re-checks the actor's numeric Telegram ID and backend permission; localized button visibility is not authorization.

English private menu:

```text
🛡 Administration

🆕 New tickets                 🟡 In progress
📝 Registrations               🛠 Problems
⚠️ Violations                  🔎 Search
📊 Statistics                  ➕ Register Minecraft account
⚙️ Server management
```

Russian private menu:

```text
🛡 Администрирование

🆕 Новые обращения             🟡 В работе
📝 Регистрации                  🛠 Проблемы
⚠️ Нарушения                   🔎 Поиск
📊 Статистика                   ➕ Зарегистрировать игровой аккаунт
⚙️ Управление сервером
```

The shared administration group uses `ADMIN_LOCALE`, independently of the ticket creator and acting moderator. For example, `ADMIN_LOCALE=ru` renders Russian cards even when the creator uses English. Moderator prompts sent to private chat use that moderator's persisted locale.

Ticket actions use stable callbacks such as `ticket:1042:take`, `ticket:1042:reply`, and `ticket:1042:approve`. Telegram cards remain projections; SQLite is authoritative. Search accepts ticket number, Minecraft nickname, Telegram numeric ID, or username snapshot.

Server management menus:

```text
EN: 🔄 Force authentication sync | 📊 Authentication status | 🔁 Resync player
RU: 🔄 Принудительная синхронизация | 📊 Состояние авторизации | 🔁 Повторно синхронизировать игрока
```

The underlying command values remain `FORCE_AUTH_SYNC`, `AUTH_STATUS`, and `RESYNC_PLAYER`. Commands are typed, targeted, permission-checked, durable, audited, and expiring. No raw shell, RCON, or Minecraft command field exists.
