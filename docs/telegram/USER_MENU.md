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
📦 Download Modpack       # only when enabled
🗺 Server Map             # only when enabled
🛠 Report a problem    ⚠️ Report a violation
📋 My tickets          👤 My account
⚙️ Settings
```

Russian:

```text
🎮 Как играть
📦 Скачать сборку         # только когда включено
🗺 Карта сервера          # только когда включено
🛠 Сообщить о проблеме    ⚠️ Сообщить о нарушении
📋 Мои обращения          👤 Мой аккаунт
⚙️ Настройки
```

Settings reopens the same English/Russian selector and immediately renders the main menu in the selected language. Problem/violation reports, registration, ticket history, account management, password changes, additional-account requests, buttons, errors, enum presentation, and UTC timestamp rendering support both locales.

How to Play uses `howToPlay.en` / `howToPlay.ru` from the UTF-8 Telegram feature YAML and falls back to the bundled localized text. `HOW_TO_PLAY_URL`, when configured, adds a detailed-guide button. Optional map and modpack reply actions expose configured HTTPS URL buttons; URLs are never callback identifiers.

When the optional registration subscription gate is enabled, Apply calls Telegram `getChatMember` before any wizard state is created. Non-members receive Subscribe and stable `registration:subscription:retry` buttons. Temporary API failures receive a localized retry and are not classified as non-members.

All Telegram-visible timestamps are presented in `Europe/Moscow`. UTC remains the database and business-logic representation.

Attachments are limited to photo, video, document, and animation references. The backend stores Telegram `file_id` and `file_unique_id`; it does not download the media. Wizard drafts remain ephemeral, while the chosen locale and submitted business data are durable.

Violation reporting asks whether the suspected player's nickname is known. Choosing Unknown continues to description/evidence and stores SQL `NULL`, not a placeholder string.
