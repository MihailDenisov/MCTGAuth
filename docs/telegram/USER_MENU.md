# Telegram User Menu

The bot uses persistent reply keyboards for top-level navigation and stable inline callback identifiers for ticket, account, and locale actions. Visible translated text is never a domain or authorization identifier. `/start`, `/register`, `/tickets`, and `/account` remain language-independent fallback commands.

`/menu` resets transient state and always opens the ordinary user-facing private menu, including for moderators. `/adm` is the dedicated administration entry point. `/cancel` cancels the current wizard without deleting submitted tickets; an administrative wizard returns to the administration menu. Every submenu or wizard provides localized Back, Cancel where applicable, and Main menu navigation.

## Initial language selection

New users see:

```text
Choose interface language / Выберите язык интерфейса

[🇬🇧 English]
[🇷🇺 Русский]
```

The callback identifiers are `locale:set:en` and `locale:set:ru`. The selected code is stored in `users.locale`. Telegram `language_code` may suggest the initial value but never overwrites a persisted choice. English is the fallback for invalid legacy values or missing translations.

## Unregistered menu

English:

```text
📝 Apply for registration
📋 Registration status    ℹ️ About the server
⚙️ Settings
```

Russian:

```text
📝 Подать заявку на регистрацию
📋 Статус регистрации    ℹ️ О сервере
⚙️ Настройки
```

## Registered menu

English:

```text
ℹ️ Server Information
🛠 Report a problem    ⚠️ Report a violation
📋 My tickets          👤 My account
⚙️ Settings
```

Russian:

```text
ℹ️ Информация о сервере
🛠 Сообщить о проблеме    ⚠️ Сообщить о нарушении
📋 Мои обращения          👤 Мой аккаунт
⚙️ Настройки
```

## Server Information

Selecting **Server Information** opens a submenu. `How to Play`, `Download Modpack`, and `Web Map` are no longer top-level buttons. The optional download and map buttons appear only when valid HTTPS URLs are enabled.

English: `📖 How to Play` · `📦 Download Modpack` (optional) · `🗺 Web Map` (optional) · `📡 Server Status` (when enabled) · `⬅️ Back`.

Russian: `📖 Как играть` · `📦 Скачать сборку` (необязательно) · `🗺 Веб-карта` (необязательно) · `📡 Статус сервера` (когда включён) · `⬅️ Назад`.

The submenu uses stable locale-independent `server_info:*` callbacks. In particular, `server_info:back` always opens the ordinary user menu, even for moderators; administration remains available separately through `/adm`.

Server Status uses the latest backend heartbeat. A heartbeat older than `heartbeatTimeoutSeconds` (default `60`) is shown as unavailable; its old count and player list are not presented as current. When fresh, the status shows online/max players and, if enabled, an inline **👥 Player List** button. The list contains active sessions and current session durations, or a localized empty-server message.

Settings reopens the same English/Russian selector and immediately renders the main menu in the selected language. Problem/violation reports, registration, ticket history, account management, password changes, additional-account requests, buttons, errors, enum presentation, and UTC timestamp rendering support both locales.

When `memberTags.enabled` is configured, Settings also contains **🏷 Chat Tag** / **🏷 Тег чата**. It lists only enabled Minecraft accounts owned by the current numeric Telegram identity plus **No tag**. No tag is the default. The backend stores an account ID, revalidates ownership on every callback, and resolves the current authoritative nickname when synchronizing the explicitly configured target chat. Telegram administrators/owners are never assigned a game tag; a user may save a preference while outside the chat or during an API outage for later reconciliation.

How to Play uses `howToPlay.en` / `howToPlay.ru` from the UTF-8 Telegram feature YAML and falls back to the bundled localized text. `HOW_TO_PLAY_URL`, when configured, adds a detailed-guide button. Optional map and modpack submenu callbacks open views containing the configured HTTPS URL buttons; URLs are never callback identifiers.

## My Account and playtime

The **My Account** submenu contains **⏱ My Playtime** / **⏱ Моё время на сервере**. With multiple owned Minecraft accounts, the bot first shows only those owned accounts for selection. The backend summary includes the active session and reports rolling last 24 hours, rolling last 7 days, and all recorded time. Overlapping intervals are merged, and ownership is checked server-side.

Blocked Telegram users receive only the localized restricted-access response and cannot start registration, tickets/reports, add-account requests, or password workflows. Bot blocking does not disable Minecraft accounts; `minecraft_accounts.enabled` remains a separate moderator control.

When the optional registration subscription gate is enabled, Apply calls Telegram `getChatMember` before any wizard state is created. Non-members receive Subscribe and stable `registration:subscription:retry` buttons. Temporary API failures receive a localized retry and are not classified as non-members.

All Telegram-visible timestamps are presented in `Europe/Moscow`. UTC remains the database and business-logic representation.

Attachments are limited to photo, video, document, and animation references. The backend stores Telegram `file_id` and `file_unique_id`; it does not download the media. Wizard drafts remain ephemeral, while the chosen locale and submitted business data are durable.

Violation reporting asks whether the suspected player's nickname is known. Choosing Unknown continues to description/evidence and stores SQL `NULL`, not a placeholder string.
