# Утилиты развёртывания на Debian VPS

Этот каталог содержит небольшой production-toolkit для backend: установка, обновление, автоматический откат к предыдущей версии, резервное копирование и восстановление SQLite, проверка состояния, журналы и очистка старых файлов. Docker и Git на VPS не требуются.

Подробности и описание мер безопасности находятся в [`docs/deployment/VPS_MANAGEMENT.md`](../../docs/deployment/VPS_MANAGEMENT.md).

## Куда помещаются файлы

После запуска `install.sh` используется такая структура:

```text
/opt/minecraft-auth/
├── current -> /opt/minecraft-auth/releases/<активный-release>
├── releases/                         # неизменяемые версии приложения
└── shared/

/opt/minecraft-auth-tools/            # копии management-скриптов
/var/lib/minecraft-auth/
└── auth-backend.db                   # постоянная SQLite БД

/var/backups/minecraft-auth/           # безопасные SQLite backup-файлы
/etc/minecraft-auth.env                # секреты и runtime-конфигурация
/etc/systemd/system/minecraft-auth.service
/var/log/minecraft-auth-deploy.log     # журнал операций deploy
```

База данных, секреты и backup-файлы никогда не помещаются внутрь release-каталогов.

## Требования к VPS

Нужны Debian, Java 21, `sqlite3`, `jq`, `curl`, `tar`, `flock` и `systemd`. Например, если репозитории вашей версии Debian предоставляют OpenJDK 21:

```bash
sudo apt update
sudo apt install openjdk-21-jre-headless sqlite3 jq curl tar util-linux
java -version
```

Если пакета OpenJDK 21 нет в подключённых репозиториях, установите другой поддерживаемый JRE 21 и до запуска `install.sh` убедитесь, что команда `java` указывает именно на него.

## 1. Подготовка дистрибутива

На компьютере разработчика из корня репозитория:

```bash
./gradlew :auth-backend:test
./gradlew :auth-backend:deploymentArchive
```

На Windows:

```powershell
.\gradlew.bat :auth-backend:test
.\gradlew.bat :auth-backend:deploymentArchive
```

Готовый SFTP-артефакт:

```text
auth-backend/build/distributions/auth-backend-<version>.tar.gz
```

Он содержит только `bin/`, `lib/` и `release.properties`. Можно также передать каталог `auth-backend/build/install/auth-backend/`, созданный задачей `installDist`.

## 2. Первая установка toolkit

Скопируйте каталог `deploy/vps` на VPS, например через SFTP:

```text
/home/authbot/uploads/vps-tools/
```

На VPS:

```bash
cd /home/authbot/uploads/vps-tools
chmod 0755 ./*.sh
sudo ./install.sh
```

Скрипт:

- проверит Java 21 и `sqlite3`;
- создаст системного пользователя `minecraft-auth`;
- подготовит application/data/backup-каталоги;
- скопирует утилиты в `/opt/minecraft-auth-tools`;
- установит и включит systemd unit;
- создаст `/etc/minecraft-auth.env`, только если файла ещё нет.

Если `/etc/minecraft-auth.env` уже существует, он не перезаписывается. После первой установки обязательно замените placeholder-значения:

```bash
sudoedit /etc/minecraft-auth.env
sudo chown root:root /etc/minecraft-auth.env
sudo chmod 0600 /etc/minecraft-auth.env
```

При обновлении с версии 0.2 добавьте в сохранённый файл окружения имя вашего бота без `@`:

```dotenv
TELEGRAM_BOT_USERNAME=ExampleBot
```

Без этой переменной backend 0.5.0 намеренно не запустится: значение нужно для корректной обработки команд вида `/menu@ExampleBot` и игнорирования команд, адресованных другим ботам в общей группе.

`install.sh` также создаёт только при отсутствии `/etc/minecraft-auth-telegram.yaml` (`root:minecraft-auth`, `0640`). Укажите его через `TELEGRAM_FEATURE_CONFIG_PATH`. В UTF-8 YAML независимо настраиваются многострочные `howToPlay.en`/`ru`, необязательная `registration.subscriptionCheck`, `serverMap` и `modpackDownload`; все дополнительные функции по умолчанию выключены. Некорректные ссылки скрываются с предупреждением, а встроенный текст остаётся fallback. Изменения загружаются при старте:

```bash
sudoedit /etc/minecraft-auth-telegram.yaml
sudo /opt/minecraft-auth-tools/restart.sh
```

Для проверки подписки бот вызывает Telegram `getChatMember`. Добавьте бота администратором обязательного канала; временная ошибка Telegram не считается отсутствием подписки и оставляет кнопку повторной проверки.

## 3. Загрузка и активация release

Загрузите архив через SFTP:

```text
Developer PC
    -> auth-backend-<version>.tar.gz
    -> /home/authbot/uploads/
    -> update.sh
    -> /opt/minecraft-auth/releases/
```

Затем:

```bash
sudo /opt/minecraft-auth-tools/update.sh \
  /home/authbot/uploads/auth-backend-0.5.0.tar.gz
```

Либо передайте распакованный `installDist`:

```bash
sudo /opt/minecraft-auth-tools/update.sh \
  /home/authbot/uploads/auth-backend
```

Перед переключением `current` скрипт:

1. проверяет пакет, metadata, Java и schema compatibility;
2. создаёт SQLite backup через `.backup`;
3. создаёт уникальный release;
4. назначает безопасные права;
5. атомарно переключает symlink;
6. запускает backend и ждёт успешный `/health`.

Если новая версия не проходит health check, symlink автоматически возвращается на предыдущий release и предыдущая версия запускается снова. База данных при таком откате автоматически не восстанавливается.

## Основные команды

```bash
sudo /opt/minecraft-auth-tools/status.sh
sudo /opt/minecraft-auth-tools/health.sh
sudo /opt/minecraft-auth-tools/start.sh
sudo /opt/minecraft-auth-tools/stop.sh
sudo /opt/minecraft-auth-tools/restart.sh

sudo /opt/minecraft-auth-tools/logs.sh
sudo /opt/minecraft-auth-tools/logs.sh -n 200
sudo /opt/minecraft-auth-tools/logs.sh -f

sudo /opt/minecraft-auth-tools/versions.sh
sudo /opt/minecraft-auth-tools/backup.sh
```

## Откат приложения

К предыдущему release:

```bash
sudo /opt/minecraft-auth-tools/rollback.sh
```

К конкретному release:

```bash
sudo /opt/minecraft-auth-tools/rollback.sh \
  2026-09-11_190500-v0.3
```

`rollback.sh` переключает только код. SQLite БД не заменяется. Если схема БД новее максимальной схемы старого release, откат блокируется. `--force-incompatible` следует использовать только после ручной проверки миграций и наличия проверенного backup.

Для автоматизации подтверждение можно передать отдельно:

```bash
sudo /opt/minecraft-auth-tools/rollback.sh --yes <release-name>
```

## Backup и restore базы данных

Создание согласованного SQLite backup:

```bash
sudo /opt/minecraft-auth-tools/backup.sh
```

Файл появится в `/var/backups/minecraft-auth/`. Для восстановления:

```bash
sudo /opt/minecraft-auth-tools/restore.sh \
  /var/backups/minecraft-auth/auth-backend-20260911T170501Z.db
```

Без интерактивного вопроса:

```bash
sudo /opt/minecraft-auth-tools/restore.sh --yes \
  /var/backups/minecraft-auth/auth-backend-20260911T170501Z.db
```

Перед заменой текущей БД `restore.sh` создаёт дополнительный emergency backup, останавливает systemd-службу, проверяет восстановленную БД, сохраняет владельца/права, запускает backend и выполняет health check.

## Полный сброс тестовых данных перед production

`reset-database.sh` — разрушительная операция. Задайте в `/etc/minecraft-auth.env` реальные `MINECRAFT_SERVICE_NAME`, `MINECRAFT_SERVICE_USER`, `MINECRAFT_SERVICE_GROUP` и абсолютный `MINECRAFT_GAME_DIR`, затем сначала выполните:

```bash
sudo /opt/minecraft-auth-tools/reset-database.sh --dry-run
```

Для подтверждаемого запуска:

```bash
sudo /opt/minecraft-auth-tools/reset-database.sh
```

Введите точную фразу `RESET ALL AUTH DATA`. `--yes` разрешён только для заранее проверенной автоматизации. Скрипт под общим lock останавливает Minecraft и backend, создаёт SQLite `.backup` и архив `mctgauth/accounts.json*` с правами `0600`, очищает runtime-таблицы одной транзакцией, проверяет foreign keys/integrity, записывает пустой локальный auth cache и запускает ранее активные службы.

Сохраняются `schema_migrations`, схема версии 4, `moderators`, `moderator_permissions` и последовательности ID. Удаляются все users, accounts/nicknames/verifier, tickets и связанные данные, password changes, pending actions, sync events, remote commands и audit. После полного сброса каждый ранее зарегистрированный пользователь должен зарегистрироваться заново. Все ранее выданные пароли Minecraft становятся недействительными.

Для restore используйте парные файлы из одного запуска: восстановите DB через `restore.sh`, при остановленных службах распакуйте соответствующий `mctgauth-cache-*.tar.gz` в каталог `mctgauth`, исправьте owner/mode и только затем запускайте обе стороны. Backend DB без соответствующего cache восстанавливать нельзя; иначе оставьте Minecraft остановленным до полной новой синхронизации.

## Очистка

По умолчанию сохраняются минимум пять новых release и десять backup-файлов. Активный и непосредственно предыдущий release защищены независимо от лимита.

Сначала выполните dry run:

```bash
sudo /opt/minecraft-auth-tools/cleanup.sh --dry-run
```

Затем очистку:

```bash
sudo /opt/minecraft-auth-tools/cleanup.sh
```

Изменение retention для одного запуска:

```bash
sudo KEEP_RELEASES=7 KEEP_BACKUPS=20 \
  /opt/minecraft-auth-tools/cleanup.sh
```

## Обновление самих утилит

Скопируйте новую версию `deploy/vps` на VPS и снова запустите:

```bash
chmod 0755 ./*.sh
sudo ./install.sh
```

Существующий `/etc/minecraft-auth.env`, release-каталоги, БД и backup-файлы будут сохранены.
После обновления toolkit сверяйте новые обязательные переменные с шаблоном в `install.sh`: существующий environment-файл не дополняется автоматически, чтобы не перезаписать секреты оператора.

## Важные ограничения

- Запускайте изменяющие состояние команды через `sudo`.
- Не распаковывайте upload вручную от `root`: `update.sh` сам проверяет archive entries и запрещает ссылки/специальные файлы.
- Не помещайте токены или `.env` в deployment archive.
- Не восстанавливайте БД только ради отката приложения.
- `update.sh`, `rollback.sh`, `restore.sh`, `reset-database.sh` и `cleanup.sh` используют общий `flock` и не выполняются одновременно.
- Если health check или rollback завершился ошибкой, смотрите `status.sh`, затем `logs.sh -n 200`.
