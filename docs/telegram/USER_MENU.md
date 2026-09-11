# Telegram User Menu

The bot uses persistent reply keyboards for top-level navigation and stable inline callback identifiers for ticket, account, and locale actions. Visible translated text is never a domain or authorization identifier. `/start`, `/register`, `/tickets`, and `/account` remain language-independent fallback commands.

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

Attachments are limited to photo, video, document, and animation references. The backend stores Telegram `file_id` and `file_unique_id`; it does not download the media. Wizard drafts remain ephemeral, while the chosen locale and submitted business data are durable.
