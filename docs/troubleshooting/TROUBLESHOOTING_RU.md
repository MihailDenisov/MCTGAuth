# Устранение неполадок MCTGAuth

Руководство предназначено для MCTGAuth 0.4.2, Java 21, Minecraft 1.21.1 и NeoForge 21.1.244 (или совместимого выпуска 21.1.x). Сначала соберите диагностику, затем меняйте конфигурацию. Никогда не отправляйте в issue или чат пароли, данные verifier, proof, nonce, salt, API-токены, токены Telegram или содержимое секретного environment/config-файла.

## Безопасные первичные проверки

На VPS выполняйте команды от имени оператора и публикуйте только код завершения и обезличенный вывод:

```bash
java -version
sudo /opt/minecraft-auth-tools/status.sh
sudo /opt/minecraft-auth-tools/health.sh
sudo /opt/minecraft-auth-tools/logs.sh -n 200
```

Endpoint health доступен только через loopback. Если нужна прямая проверка, не добавляйте заголовки с токенами:

```bash
curl --fail --silent --show-error --max-time 5 http://127.0.0.1:8080/health
```

В локальном Windows checkout:

```powershell
java -version
.\gradlew.bat :auth-backend:test
.\gradlew.bat :auth-neoforge-server:verifyServerJar
```

При публикации логов скройте Telegram ID, UUID игроков, пути к БД, имена хостов и authorization header, если они не нужны для закрытого расследования. Приложение и deployment toolkit не должны записывать секреты в логи.

## Ошибка Java или запуска

Backend и VPS toolkit требуют Java 21. Другая major-версия может помешать запуску или вызвать вводящие в заблуждение ошибки Gradle/runtime. Проверьте, что команда `java`, используемая systemd, также указывает на Java 21, а не только локальная переменная `JAVA_HOME`.

Если `install.sh` сообщает об отсутствии зависимости, установите поддерживаемые Java 21, `sqlite3`, `curl`, `tar`, `flock` и systemd на Debian. Повторите `java -version` и безопасные проверки сервиса. Не обходите проверку обязательных зависимостей.

## Backend остановлен или unhealthy

Сначала используйте `status.sh`, `health.sh` и `logs.sh -n 200`. Проверьте наличие `/etc/minecraft-auth.env`, владельца `root:root` и права `0600`, не печатая файл:

```bash
sudo stat -c '%U:%G %a %n' /etc/minecraft-auth.env
sudo systemctl status minecraft-auth.service --no-pager
```

При необходимости проверьте только имена ключей:

```bash
sudo awk -F= '/^[A-Z][A-Z0-9_]*=/{print $1}' /etc/minecraft-auth.env
```

Нужны `TELEGRAM_BOT_TOKEN`, `TELEGRAM_BOT_USERNAME` (без `@`), `TELEGRAM_ADMIN_CHAT_ID`, `MODERATOR_TELEGRAM_IDS`, `AUTH_API_TOKEN`, `AUTH_DATABASE_PATH` и `HOW_TO_PLAY_URL`. `HTTP_BIND_ADDRESS` должен оставаться loopback (`127.0.0.1` или `localhost`). `SERVER_ID` должен совпадать с настройкой сервера.

При обновлении с 0.2 существующий environment-файл намеренно сохраняется и автоматически не дополняется. Добавьте новые обязательные ключи защищённым редактором, затем перезапустите сервис и снова проверьте health. Не заменяйте настоящий токен placeholder-ом из example-файла.

## Не работают Telegram-команды или модерация

Проверьте имя бота без `@`: `/menu@ExampleBot` обрабатывается этим ботом, а команды для другого бота в общей группе игнорируются. Проверьте ID административного чата и числовые Telegram ID модераторов. Username является только отображаемыми данными и не даёт права доступа.

Проверьте обезличенную ошибку запуска и локаль/меню в логах backend. Не исправляйте проблему публикацией токена или прямым назначением пользователя модератором в сообщении. Источник истины — БД; сообщения Telegram являются только представлением состояния тикетов.

## Minecraft-сервер не синхронизируется

Серверному моду нужны Java 21, совместимый `mctgauth-server-0.4.2.jar` и отдельный серверный `config/mctgauth-server.toml`. Проверьте, что `backend.baseUrl` — HTTPS URL backend, `backend.serverId` равен backend `SERVER_ID`, а `backend.apiToken` совпадает с `AUTH_API_TOKEN`. Дополнительная переменная `MCTGAUTH_API_TOKEN` имеет приоритет над TOML. Сравнивайте токены только приватно и не выводите их.

Для синхронизации не нужен входящий порт: сервер выполняет исходящие HTTPS-запросы. При отсутствии токена синхронизация отключается, но ранее синхронизированные включённые аккаунты остаются доступны из локального cache. Исправляйте неверную конфигурацию, не отключая проверку TLS.

До deployment проверьте границы server JAR:

```powershell
.\gradlew.bat :auth-neoforge-server:verifyServerJar
```

## Игрок не может войти

Убедитесь, что client и server моды установлены и совместимы, аккаунт одобрен и синхронизирован. До успешной аутентификации quarantine намеренно блокирует движение, взаимодействия, инвентарь, перенос предметов, урон, чат и команды.

Клиент хранит пароль в открытом виде в `.minecraft/config/mctgauth-client.json`; защищайте этот файл и никогда не включайте его в диагностику. Откройте **MCTGAuth** на титульном экране или **Mods → MCTGAuth Client → Config**, сохраните актуальный пароль и повторите вход. После смены пароля backend сохранённый пароль может быть отклонён.

Причины `ACCOUNT_NOT_SYNCHRONIZED`, `ACCOUNT_DISABLED`, `AUTH_TIMEOUT`, `TOO_MANY_ATTEMPTS`, `PROTOCOL_MISMATCH`, `CLIENT_MISSING` и `INTERNAL_AUTH_ERROR` завершают текущий цикл аутентификации. MCTGAuth не вызывает API подключения Minecraft и не планирует автоматический reconnect. Вернитесь на сервер или подключитесь вручную; подключение без записи MCTGAuth `Starting reconnect` инициировано не этим модом.

## Риск AutoModPack и стороннего reconnect

AutoModPack, reconnect-утилиты, launcher и server wrapper могут устанавливать, удалять, заменять моды и самостоятельно перезапускать подключения. На время воспроизведения отключите такую автоматизацию или изолируйте её. Проверьте точные версии и хэши JAR приватно, затем протестируйте только с нужными артефактами MCTGAuth и зависимостями. Автоматический reconnect не доказывает успешную аутентификацию MCTGAuth.

## Повреждённый JSON cache сервера

Cache сервера находится в `<server>/mctgauth/accounts.json`; `accounts.json.bak` — предыдущая backup-копия, а `accounts.json.tmp` может остаться после прерванной записи. Повреждённые данные сохраняются, а аутентификация завершается отказом. Остановите сервер до проверки или восстановления файлов. Не удаляйте `accounts.json`, не редактируйте verifier вручную и не копируйте cache другого сервера. Сохраните файлы для расследования и восстанавливайте только заведомо исправную backup-копию после проверки `server_id`, владельца аккаунтов и прав. После исправления подключения backend может заново заполнить cache одобренными аккаунтами.

## SQLite, deployment, обновление или rollback

Откат приложения и восстановление БД — разные операции. Сначала соберите:

```bash
sudo /opt/minecraft-auth-tools/status.sh
sudo /opt/minecraft-auth-tools/health.sh
sudo /opt/minecraft-auth-tools/versions.sh
sudo /opt/minecraft-auth-tools/logs.sh -n 200
```

`update.sh` проверяет archive/schema, создаёт backup SQLite, атомарно переключает symlink `current`, запускает сервис и проверяет `/health`. Если health завершается ошибкой после переключения, предыдущий release восстанавливается автоматически, а проблемный остаётся для диагностики. Откат кода не восстанавливает БД. Новая schema может быть несовместима со старым release; не используйте `--force-incompatible` без проверки миграций и проверенной backup.

При проблемах SQLite используйте backup/restore toolkit, а не копирование WAL-файлов:

```bash
sudo /opt/minecraft-auth-tools/backup.sh
sudo /opt/minecraft-auth-tools/restore.sh /var/backups/minecraft-auth/<known-good-backup>.db
```

`restore.sh` остановит сервис, проверит backup, сохранит emergency backup, восстановит владельца/права, запустит backend и проверит health. Во время расследования не запускайте очистку; сначала используйте `cleanup.sh --dry-run` и сохраняйте активный и предыдущий release.

## Что приложить к отчёту

Укажите обезличенные версию MCTGAuth, major-версию Java, версии Minecraft/NeoForge, затронутый модуль (backend/client/server), точный reason code, время с часовым поясом и коды завершения безопасных проверок. Фрагменты stack trace публикуйте только после удаления паролей, токенов, proof, Telegram ID, UUID и приватных инфраструктурных путей.
