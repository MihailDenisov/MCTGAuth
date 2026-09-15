# MCTGAuth backend 0.5.0

[English version](README.md)

Самостоятельный backend Java 21. Он запускает Telegram-бота через long polling и является источником истины для пользователей, заявок, Minecraft-аккаунтов, Argon2id verifier, аудита, событий синхронизации и типизированных remote-команд. Minecraft-модули используют HTTPS API и не имеют доступа к этой SQLite БД.

## Требования и безопасность

Нужны Java 21 (JDK/JRE), исходящий HTTPS к `api.telegram.org`, бот BotFather, приватная admin-группа и каталог SQLite с правом записи. Процесс читает переменные окружения напрямую и не загружает `.env`. Секреты не должны попадать в Git; production-файл окружения — `root:root`, права `0600`. Открытые пароли Minecraft выдаются только получателю, не сохраняются, не логируются и не синхронизируются. Авторизация использует числовые Telegram ID, username — только отображаемые данные.

## Конфигурация

`BackendConfig` проверяет обязательные значения и форматы при запуске.

| Переменная | Обязательна | По умолчанию/пример | Секрет? и поведение |
|---|---|---|---|
| `TELEGRAM_BOT_TOKEN` | Да | `<токен BotFather>` | Секрет long polling; не логировать/коммитить. |
| `TELEGRAM_BOT_USERNAME` | Да | `ExampleBot` | Нет; 5–32 `[A-Za-z0-9_]`, без `@`; фильтр адресации команд. |
| `TELEGRAM_ADMIN_CHAT_ID` | Да | `-1000000000000` | Нет; ненулевой числовой ID приватной группы, обычно отрицательный. |
| `MODERATOR_TELEGRAM_IDS` | Да | `123456789,987654321` | Нет; положительные числовые ID через запятую. |
| `AUTH_DATABASE_PATH` | Нет | `./data/auth-backend.db` | Чувствительный путь; service account нужен доступ к родителю. |
| `HOW_TO_PLAY_URL` | Нет | `https://t.me/example/1` | Необязательный абсолютный HTTPS URL подробной инструкции. |
| `TELEGRAM_FEATURE_CONFIG_PATH` | Нет | `./config/telegram-features.yaml` | UTF-8 YAML с локализованным текстом, проверкой подписки, картой и сборкой. |
| `HTTP_BIND_ADDRESS` | Нет | `127.0.0.1` | Нет; только loopback, публичный bind отклоняется. |
| `HTTP_PORT` | Нет | `8080` | Нет; от 1 до 65535. |
| `AUTH_API_TOKEN` | Да | `<32+ случайных символа>` | Bearer-секрет; 32–512 непробельных символов. |
| `SERVER_ID` | Да | `survival-main` | Нет; стабильный ID `[A-Za-z0-9._-]`, 1–64 символа. |
| `ADMIN_LOCALE` | Нет | `en` или `ru` | Язык общей admin-группы; по умолчанию `en`. |
| `DEFAULT_USER_LOCALE` | Нет | `en` или `ru` | Начальный язык private-чата; по умолчанию `en`. |

Примеры — placeholders. Настоящие значения задавайте только в окружении процесса или `/etc/minecraft-auth.env`, не печатайте файл. `AUTH_API_TOKEN` должен совпадать с `backend.apiToken` server-мода или override `MCTGAUTH_API_TOKEN`.

Формат описан в [`telegram-features.example.yaml`](src/main/resources/telegram-features.example.yaml). `howToPlay.en` и `howToPlay.ru` поддерживают многострочный UTF-8; отсутствующее/пустое значение использует встроенный перевод. `serverMap` и `modpackDownload` по умолчанию выключены и показываются только с корректным абсолютным HTTPS URL. `registration.subscriptionCheck.enabled` ограничивает только начало новой регистрации. Подписанными считаются creator, administrator, member и restricted с `is_member=true`; ошибка Telegram показывает повторную проверку, а не ложное отсутствие подписки. Для надёжного `getChatMember` назначьте бота администратором обязательного канала. Файл загружается при старте; после изменения перезапустите backend.

## Telegram

Поддерживаются `/start`, `/menu`, `/adm`, `/admin` (alias), `/register`, `/tickets`, `/account`, `/cancel`; работают формы `/command` и `/command@ConfiguredBotUsername`, а обращённые к другому боту команды игнорируются. `/start` даёт новому пользователю выбор языка. В private-чате `/menu` всегда открывает обычное меню, даже модератору; `/adm`/`/admin` требуют включённый числовой ID модератора. В настроенной admin-группе `/menu`, `/adm`, `/cancel` открывают или сбрасывают inline-панель и повторно проверяют права. Другие группы игнорируются.

Пользователь регистрируется, смотрит заявки/аккаунты, сообщает о проблеме или нарушении, запрашивает дополнительный аккаунт, меняет пароль и выбирает English/Russian. Одобрение регистрации/дополнительного аккаунта атомарно создаёт аккаунт, verifier, аудит и sync event; активная регистрация не дублируется. Модератор фильтрует/ищет заявки, отвечает privately, одобряет/отклоняет допустимые заявки, меняет состояние, смотрит статистику/аккаунты и ставит `FORCE_AUTH_SYNC`, `AUTH_STATUS`, `RESYNC_PLAYER`. Отключение требует причины/подтверждения; изменения аудируются, уведомляют владельца, создают sync и idempotentны. Карточки Telegram — представления, SQLite — источник истины. Pending-ответ модератора private, назначен по numeric ID и истекает через 30 минут.

Отключённый синхронизированный аккаунт остаётся запрещённым с `ACCOUNT_DISABLED` при недоступном backend. `ACCOUNT_DISABLED` и `ACCOUNT_ENABLED` сохраняются локально до ACK; при `disconnectPlayerWhenAccountDisabled=true` сервер отключает онлайн-игрока в основном потоке. Изменение относится только к выбранному Minecraft-аккаунту и не затрагивает остальные аккаунты владельца.

В БД и бизнес-логике timestamp — UTC `Instant`; в Telegram — `Europe/Moscow`, `dd.MM.yyyy HH:mm MSK`/`МСК`.

## HTTP API

Встроенный сервер слушает только loopback. `/health` и `/version` не требуют bearer, но не должны публиковаться. Все `/api/v1/server/*` требуют `Authorization: Bearer <AUTH_API_TOKEN>` и `server_id`; JSON ограничен 16 KiB.

| Метод/путь | Ответ и назначение |
|---|---|
| `GET /health` | `200` при исправной БД, иначе `503`; status, database/Telegram, version, commit, schemaVersion. |
| `GET /version` | `200`; только application, version, commit, buildTime, schemaVersion. |
| `GET /api/v1/server/events?server_id=<id>` | `200`; pending sync events настроенного сервера. |
| `POST /api/v1/server/events/<event-id>/ack?server_id=<id>` | `200 acknowledged` или `404 not_found`; повтор безопасен. |
| `GET /api/v1/server/commands?server_id=<id>` | `200`; упорядоченные неистёкшие typed-команды. |
| `POST /api/v1/server/commands/<command-id>/started?server_id=<id>` | `200 running` или `409 invalid_state`. |
| `POST /api/v1/server/commands/<command-id>/result?server_id=<id>` | JSON `status` `SUCCESS`/`FAILED`, структурированный `result`, необязательный safe `errorMessage`; `200` или `409 invalid_state`. |

Ошибки: `401`/`unauthorized` с `WWW-Authenticate: Bearer`; `405`/`method_not_allowed`; неверные ID/JSON/status/query — `400`/`invalid_request`; неизвестные маршрут/ID — `404`/`not_found`; отклонённый workflow — `403`/`409` `request_rejected`. Ответы JSON с `Cache-Control: no-store`. Принимается только настроенный `SERVER_ID`; ID, expiry, ack и переходы состояний проверяются idempotently. Подробности: [`docs/api/SYNC_API.md`](../docs/api/SYNC_API.md), [`docs/api/REMOTE_COMMAND_API.md`](../docs/api/REMOTE_COMMAND_API.md). Произвольных shell/RCON-команд нет.

## SQLite, сборка и запуск

При открытии backend создаёт родительский каталог, включает foreign keys, busy timeout 5 секунд, WAL и упорядоченные миграции. Версия схемы доступна в `/health` и `/version`; metadata — `src/main/resources/db/migration/migrations.txt`. В БД хранится UTC, Moscow используется только для Telegram. Перед backup/restore останавливайте service. Не удаляйте и не редактируйте эту authoritative БД вручную.

```powershell
.\gradlew.bat :auth-backend:test
.\gradlew.bat :auth-backend:installDist
.\gradlew.bat :auth-backend:deploymentArchive
```

Linux использует `./gradlew` с теми же задачами. Дистрибутив: `auth-backend/build/install/auth-backend/`; archive: `auth-backend/build/distributions/auth-backend-<version>.tar.gz`. Задайте переменные и используйте `:auth-backend:run` или launcher; не помещайте секреты в историю команд.

## Managed deployment и операции

Используйте канонический Debian toolkit: [`deploy/vps/README.md`](../deploy/vps/README.md) и [`docs/deployment/VPS_MANAGEMENT.md`](../docs/deployment/VPS_MANAGEMENT.md). Он отделяет releases от данных `/var/lib/minecraft-auth` и `/etc/minecraft-auth.env`, проверяет archive/schema, делает SQLite backup, health-check и защищённые rollback/restore. Не заменяйте `/opt/minecraft-auth` напрямую по legacy-процедуре.

```bash
sudo /opt/minecraft-auth-tools/status.sh
sudo /opt/minecraft-auth-tools/health.sh
sudo /opt/minecraft-auth-tools/logs.sh -n 200
```

Java, Telegram, sync, cache, deployment, rollback и redaction: [`docs/troubleshooting/TROUBLESHOOTING_RU.md`](../docs/troubleshooting/TROUBLESHOOTING_RU.md).
