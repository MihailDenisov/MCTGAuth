# Telegram Ticket Flows

All ticket types share the global `tickets.id` sequence. Internal ticket types and statuses remain stable English enum values in SQLite; only presentation is localized.

Examples:

```text
PROBLEM     -> Problem / Проблема
VIOLATION   -> Violation / Нарушение
OPEN        -> Open / Открыто
IN_PROGRESS -> In progress / В работе
```

Validated transitions are:

```text
OPEN -> IN_PROGRESS
OPEN -> REJECTED
IN_PROGRESS -> WAITING_USER
IN_PROGRESS -> RESOLVED
IN_PROGRESS -> REJECTED
WAITING_USER -> IN_PROGRESS
WAITING_USER -> REJECTED
RESOLVED -> CLOSED
REJECTED -> CLOSED
```

## Registration

```text
EN: Your Minecraft nickname -> Licensed account? -> Age -> Discovery/gameplay experience -> Confirm
RU: Ваш ник в Minecraft -> Лицензионный аккаунт? -> Возраст -> Откуда узнали / игровой опыт -> Подтвердить
```

Yes/No buttons are localized and mapped to stable `BotAction.YES`/`BotAction.NO` actions. A user does not need to type an English literal.

## Problem report

```text
EN: Select account -> Describe problem -> Add attachments -> Submit
RU: Выберите аккаунт -> Опишите проблему -> Добавьте файлы -> Отправить
```

## Violation report

```text
EN: Select account -> Nickname known? -> Optional reported nickname -> Description/evidence -> Submit
RU: Выберите аккаунт -> Ник известен? -> Необязательный ник нарушителя -> Описание/доказательства -> Отправить
```

## Account operations

Password confirmation, generated-password delivery, add-account requests, ticket pagination/details, user/moderator replies, and all terminal notifications render using the recipient's locale. Admin cards and user-reply projections use `ADMIN_LOCALE`.

Registration and add-account approval atomically create the account, password verifier, audit record, and synchronization event. Plaintext generated passwords are only passed for immediate localized delivery and are never stored. Timestamps remain UTC in the database and are rendered centrally as Moscow time only at the Telegram boundary.
