# Руководство развёртывания на VPS

Документ описывает публичные скрипты из `deploy/vps/`. Они рассчитаны на Debian VPS с Java 21, SQLite, curl, tar, util-linux/flock и systemd. Изменяющие состояние команды запускайте от `root` (обычно через `sudo`). Git и Docker на VPS не нужны.

## Каталоги и служба

`install.sh` создаёт или сохраняет такую структуру:

```text
/opt/minecraft-auth/current -> /opt/minecraft-auth/releases/<release>
/opt/minecraft-auth/releases/       # релизы приложения
/opt/minecraft-auth/shared/
/opt/minecraft-auth-tools/          # установленные скрипты
/var/lib/minecraft-auth/            # база данных
/var/backups/minecraft-auth/        # SQLite backup
/etc/minecraft-auth.env             # конфигурация только для root
/etc/systemd/system/minecraft-auth.service
/var/log/minecraft-auth-deploy.log
```

Служба работает как `minecraft-auth:minecraft-auth` из `/var/lib/minecraft-auth`, с `NoNewPrivileges`, `PrivateTmp`, `ProtectHome`, `ProtectSystem=strict`, `UMask=0027` и записью только в `/var/lib/minecraft-auth`. Приложение запускается из `current/bin/auth-backend`; systemd перезапускает его после сбоя.

`AUTH_DATABASE_PATH` должен быть абсолютным путём внутри `/var/lib/minecraft-auth`. `HTTP_BIND_ADDRESS` обязан быть `127.0.0.1`, `localhost` или `::1`; `HTTP_PORT` — от 1 до 65535. Проверка состояния обращается к `/health`, использует тайм-аут curl пять секунд и 30 попыток с интервалом в секунду. `HEALTH_TIMEOUT` и `HEALTH_RETRIES` можно переопределить в окружении при необходимости.

## Сборка и первая установка

На доверенной машине выполните `./gradlew :auth-backend:test` и `./gradlew :auth-backend:deploymentArchive` (либо Windows-wrapper). Архив находится в `auth-backend/build/distributions/auth-backend-<version>.tar.gz`; в его корне находятся `bin/`, `lib/` и `release.properties`. Также принимается каталог `installDist` с такой же структурой.

```bash
cd /home/authbot/uploads/vps-tools
chmod 0755 ./*.sh
sudo ./install.sh [installDist-directory-or-tar.gz]
```

`install.sh` требует root и Java 21. Он создаёт служебную учётную запись, каталоги, копии management-скриптов, unit-файл, файл окружения-заготовку с правами 0600 (если его не было) и пустую SQLite БД (если её не было), затем перезагружает конфигурацию systemd и включает службу, но не запускает её. Переданный пакет передаётся в `update.sh`. Более одного аргумента отклоняется. Существующие environment, релизы, БД и backup сохраняются.

До запуска службы замените все placeholder-значения в `/etc/minecraft-auth.env`. При последующих обновлениях toolkit этот файл автоматически не дополняется.

## Публичные команды

Ниже можно использовать путь к установленной копии скрипта. Если не указано иное, неверный аргумент или ошибка внешней команды завершают процесс с ненулевым кодом (`set -Eeuo pipefail`).

| Скрипт и синтаксис | Права | Эффекты и поведение при ошибке |
| --- | --- | --- |
| `update.sh <installDist-directory-or-tar.gz>` | root, lock | Проверяет обычные файлы/каталоги, имена архива, обязательные файлы, metadata и диапазон схемы; создаёт SQLite backup; устанавливает релиз; атомарно переключает `current`; перезапускает службу и проверяет health. При ошибке возвращает ссылку на старый релиз и проверяет его; ошибочный релиз оставляет для диагностики. |
| `backup.sh [--quiet]` | root | Делает SQLite `.backup`, задаёт `0640 root:root`, выполняет `PRAGMA integrity_check` и печатает путь. `--quiet` печатает только путь. При совпадении создаётся уникальный суффикс. |
| `restore.sh [--yes] <backup.db>` | root, lock | Принимает только обычный проверенный backup из backup-каталога и целую БД. Без `--yes` требует точный ответ `yes`; создаёт emergency backup, восстанавливает временную БД, останавливает службу, удаляет WAL/SHM, заменяет БД и запускает/проверяет службу. При плохом health восстановленная БД остаётся, путь emergency backup сообщается. |
| `rollback.sh [--yes] [--force-incompatible] [release-name]` | root, lock | По умолчанию выбирает предыдущий релиз либо указанный; проверяет его и блокирует откат, если схема БД новее `max.schema.version`, кроме режима force. Меняет только код. При плохом health возвращает исходный релиз; если и он не запускается, сообщает, что оба релиза нездоровы. |
| `cleanup.sh [--dry-run]` | root, lock | По умолчанию `KEEP_RELEASES=5`, `KEEP_BACKUPS=10`. Сохраняет минимум два релиза, защищает активный/предыдущий и удаляет только проверенные старые цели. Dry-run ничего не удаляет. |
| `start.sh` | root | Выполняет `systemctl start minecraft-auth.service`; health не ожидается. |
| `stop.sh` | root | Выполняет `systemctl stop minecraft-auth.service`. |
| `restart.sh` | root | Перезапускает службу и ждёт health. При провале health возвращает ненулевой код. |
| `health.sh` | любой пользователь с доступом к конфигурации/API | Проверяет loopback `/health`, печатает результат и возвращает ошибку при провале. |
| `status.sh` | обычно `sudo` | Показывает состояние systemd, текущий релиз/версию, БД/схему, health, uptime, место на диске и число backup. |
| `versions.sh` | обычно `sudo` | Показывает текущий/предыдущий релиз, версию приложения, схему БД и все каталоги релизов. |
| `logs.sh [-f] [-n <1..100000>]` | доступ к journal | По умолчанию показывает 100 строк службы. `-n` меняет число, `-f` включает follow. Неизвестные параметры и неверное число строк отклоняются. |

Общий `common.sh` не является командой оператора. Вложенный `tests/safety_test.sh` — тестовый помощник и `install.sh` его не копирует.

## Транзакция обновления и совместимость

```text
проверка пакета и metadata/диапазона схемы
 -> SQLite backup
 -> копирование и проверка релиза
 -> остановка службы
 -> атомарное переключение current
 -> запуск и опрос /health
```

Для update в `release.properties` обязательны `application.version`, `min.schema.version` и `max.schema.version`. Схема БД должна входить в этот диапазон. Rollback проверяет только максимальную схему цели и никогда не откатывает БД. Не удаляйте ошибочный релиз до просмотра журналов; позднее его может удалить `cleanup.sh`.

## Обычные операции

```bash
sudo /opt/minecraft-auth-tools/status.sh
sudo /opt/minecraft-auth-tools/health.sh
sudo /opt/minecraft-auth-tools/logs.sh -n 200
sudo /opt/minecraft-auth-tools/logs.sh -f
sudo /opt/minecraft-auth-tools/versions.sh
sudo /opt/minecraft-auth-tools/restart.sh
sudo /opt/minecraft-auth-tools/backup.sh
sudo /opt/minecraft-auth-tools/cleanup.sh --dry-run
sudo /opt/minecraft-auth-tools/cleanup.sh
```

Не помещайте секреты в архив и не распаковывайте upload вручную от root. `update.sh` сам выполняет проверки безопасности. Update, rollback, restore и cleanup используют общий lock и не выполняются одновременно. Изменяющие состояние операции записывают UTC-время, операцию, релизы, результат и очищенное имя оператора в `/var/log/minecraft-auth-deploy.log`; секреты не записываются.
