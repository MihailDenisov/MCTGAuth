# API телеметрии (0.6.0)

Все запросы инициирует Minecraft-сервер через настроенный HTTPS URL backend. Каждый endpoint требует тот же Bearer token длиной не менее 32 символов, что и синхронизация; backend проверяет совпадение `serverId` с `SERVER_ID`. Заголовки авторизации не журналируются.

- `POST /api/v1/servers/heartbeat`: `serverId`, уникальный для процесса `serverInstanceId`, `startedAt`, `occurredAt`, `onlinePlayers`, `maxPlayers`.
- `POST /api/v1/servers/player-events`: стабильный `eventId`, ID сервера/процесса, `PLAYER_JOINED` или `PLAYER_LEFT`, авторитетный `minecraftAccountId`, ник только для отображения и `occurredAt`.
- `POST /api/v1/servers/shutdown`: ID сервера/процесса и `occurredAt`.

Heartbeat по умолчанию отправляется каждые 20 секунд; пример timeout Telegram — 60 секунд. События игроков атомарно хранятся в `mctgauth/telemetry-events.json`, отправляются по порядку вне game thread, повторяются с ограниченной задержкой и дедуплицируются по `eventId`. Лимит журнала по умолчанию — 10 000 событий.

Принятие heartbeat транзакционно. `200 accepted` означает, что INSERT/UPDATE `server_status` завершился commit. Повторные heartbeat обновляют существующую строку `serverId`; тот же instance сохраняет `started_at`, более новый instance заменяет метаданные, а каждый применённый heartbeat обновляет время/счётчики и очищает `shutdown_at`. Устаревший heartbeat получает `409`, ошибка сохранения — `500`.

При появлении нового instance открытые сессии прежнего процесса закрываются с `SERVER_LOST` по последнему достоверному heartbeat. Недоказуемое время между heartbeat и сбоем не учитывается.
