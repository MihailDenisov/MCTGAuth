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
🎮 How to play
🛠 Report a problem    ⚠️ Report a violation
📋 My tickets          👤 My account
⚙️ Settings
```

Russian:

```text
🎮 Как играть
🛠 Сообщить о проблеме    ⚠️ Сообщить о нарушении
📋 Мои обращения          👤 Мой аккаунт
⚙️ Настройки
```

Settings reopens the same English/Russian selector and immediately renders the main menu in the selected language. Problem/violation reports, registration, ticket history, account management, password changes, additional-account requests, buttons, errors, enum presentation, and UTC timestamp rendering support both locales.

How to Play always explains that the server uses Minecraft 1.21.1 NeoForge and that players can install the prepared modpack or AutoModPack. The pinned chat message remains the textual source of detailed instructions; `HOW_TO_PLAY_URL`, when configured, adds a convenience button only.

All Telegram-visible timestamps are presented in `Europe/Moscow`. UTC remains the database and business-logic representation.

Attachments are limited to photo, video, document, and animation references. The backend stores Telegram `file_id` and `file_unique_id`; it does not download the media. Wizard drafts remain ephemeral, while the chosen locale and submitted business data are durable.

Violation reporting asks whether the suspected player's nickname is known. Choosing Unknown continues to description/evidence and stores SQL `NULL`, not a placeholder string.
