# MCTGAuth Server

Мод NeoForge 1.21.1 только для выделенного сервера. Требуется Java 21, Minecraft 1.21.1 и NeoForge 21.1.244 (либо совместимая версия 1.21.x). Установите только `build/libs/mctgauth-server-0.4.2.jar` в `mods/` выделенного сервера; не распространяйте этот JAR игрокам.

Сервер хранит Argon2id-верификаторы и метаданные идемпотентности в `<server>/mctgauth/accounts.json` (схема 1), используя при записи `accounts.json.tmp` и `accounts.json.bak`. Кэш не содержит открытых паролей; уже синхронизированные аккаунты остаются доступными при недоступности backend.

## Сборка и конфигурация

```powershell
.\gradlew.bat :auth-neoforge-server:test
.\gradlew.bat :auth-neoforge-server:verifyServerJar
.\gradlew.bat :auth-neoforge-server:build
.\gradlew.bat :auth-neoforge-server:runServer
```

NeoForge создаёт несинхронизируемый серверный файл `config/mctgauth-server.toml`. В нём находятся `[backend]` (`baseUrl`, `serverId`, `apiToken`, `pollIntervalSeconds=10`, `connectTimeoutSeconds=5`, `requestTimeoutSeconds=15`), `[authentication]` (`timeoutSeconds=60`, `maxAttempts=3`) и `[sync]` (`batchSize=100`, `allowInsecureHttpForDevelopment=false`, `disconnectPlayerWhenAccountDisabled=true`). Защитите файл как содержащий секрет.

`MCTGAUTH_API_TOKEN` — необязательное переопределение с приоритетом над `backend.apiToken`. Без токена синхронизация и удалённые команды отключаются, но локальный кэш продолжает работать. Используется исходящий HTTPS; входящий порт не нужен. HTTP разрешается только явным флагом разработки.

## Команды и поведение

Команды доступны с уровнем прав 2+: `/mcauth status`, `/mcauth sync`, `/mcauth player <name>`. `/mcauth resync <name>` не поддерживается текущим API backend, `/mcauth reload` не поддерживается — перезапустите сервер после изменения конфигурации. Неизвестные команды отклоняются безопасно.

До успешной аутентификации игрок находится в карантине: блокируются движение, контейнеры, взаимодействия, выбрасывание/подбор предметов, урон, чат, команды и смена измерения. Сервер первым объявляет `server_hello`, принимает `client_hello` и только затем выдаёт challenge. Протокол версии 1 использует nonce-связанные proof. В логах нет токенов и открытых паролей. Причины отказа: `INVALID_CREDENTIALS`, `AUTH_TIMEOUT`, `TOO_MANY_ATTEMPTS`, `PROTOCOL_MISMATCH`, `CLIENT_MISSING`, `ACCOUNT_DISABLED`, `ACCOUNT_NOT_SYNCHRONIZED`, `INTERNAL_AUTH_ERROR`.

Подробнее: [`docs/NEOFORGE_SERVER_DEPLOYMENT.md`](../docs/NEOFORGE_SERVER_DEPLOYMENT.md), [`docs/MCTGAUTH_SERVER.md`](../docs/MCTGAUTH_SERVER.md), [`docs/api/SYNC_API.md`](../docs/api/SYNC_API.md), [`docs/api/REMOTE_COMMAND_API.md`](../docs/api/REMOTE_COMMAND_API.md).
