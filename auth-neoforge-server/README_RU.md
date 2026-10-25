# MCTGAuth Server

Мод NeoForge 1.21.1 только для выделенного сервера. Требуется Java 21, Minecraft 1.21.1 и NeoForge 21.1.244 (либо совместимая версия 1.21.x). Установите только `build/libs/mctgauth-server-0.6.5.jar` в `mods/` выделенного сервера; не распространяйте этот JAR игрокам.

Сервер хранит Argon2id-верификаторы, непрозрачные device ID, клиентские digest сигналов, известные device-ban и метаданные идемпотентности в `<server>/mctgauth/accounts.json` (схема 2). Кэш не содержит открытых паролей или исходных аппаратных идентификаторов; известные блокировки действуют при недоступности backend.

## Сборка и конфигурация

```powershell
.\gradlew.bat :auth-neoforge-server:test
.\gradlew.bat :auth-neoforge-server:verifyServerJar
.\gradlew.bat :auth-neoforge-server:build
.\gradlew.bat :auth-neoforge-server:runServer
```

NeoForge создаёт несинхронизируемый серверный файл `config/mctgauth-server.toml`. В нём находятся `[backend]` (`baseUrl`, `serverId`, `apiToken`, `pollIntervalSeconds=10`, `connectTimeoutSeconds=5`, `requestTimeoutSeconds=15`), `[authentication]` (`timeoutSeconds=60`, `maxAttempts=3`), `[sync]` (`batchSize=100`) и `[telemetry]` (`enabled=true`, `heartbeatIntervalSeconds=20`, `maxQueuedPlayerEvents=10000`). Защитите файл как содержащий секрет. События игроков хранятся в `mctgauth/telemetry-events.json` и отправляются вне login/logout/tick thread; см. [`docs/api/TELEMETRY_API_RU.md`](../docs/api/TELEMETRY_API_RU.md).

`MCTGAUTH_API_TOKEN` — необязательное переопределение с приоритетом над `backend.apiToken`. Без токена синхронизация и удалённые команды отключаются, но локальный кэш продолжает работать. Используется исходящий HTTPS; входящий порт не нужен. HTTP разрешается только явным флагом разработки.

## Команды и поведение

Команды доступны с уровнем прав 2+: `/mcauth status`, `/mcauth sync`, `/mcauth player <name>`. `/mcauth resync <name>` не поддерживается текущим API backend, `/mcauth reload` не поддерживается — перезапустите сервер после изменения конфигурации. Неизвестные команды отклоняются безопасно.

До успешной аутентификации игрок находится в карантине. Сервер объявляет `server_hello`, проверяет proof, затем завершает backend/кэшированную проверку устройства. В 0.6.5 используется протокол 2 с обязательной fingerprint-возможностью и отдельными ошибками device ban/недоступности проверки. В логах нет токенов, паролей, proof или аппаратных значений.

Подробнее: [`docs/NEOFORGE_SERVER_DEPLOYMENT.md`](../docs/NEOFORGE_SERVER_DEPLOYMENT.md), [`docs/MCTGAUTH_SERVER.md`](../docs/MCTGAUTH_SERVER.md), [`docs/api/SYNC_API.md`](../docs/api/SYNC_API.md), [`docs/api/REMOTE_COMMAND_API.md`](../docs/api/REMOTE_COMMAND_API.md).
