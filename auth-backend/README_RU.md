# Установка бэкенда авторизации

[English version](README.md)

Бэкенд представляет собой самостоятельное приложение на Java 21. Оно запускает Telegram-бота в режиме long polling и хранит в SQLite основные данные о регистрации, модерации, аудите и синхронизации.

Бэкенд включает кнопочные меню Telegram для пользователей и администраторов, упорядоченные миграции SQLite, локальный HTTP API для проверки состояния и синхронизации, а также надежно сохраненную очередь типизированных удаленных команд. Потребитель на стороне Minecraft/NeoForge реализуется отдельно.

## Требования

- 64-разрядная JDK или JRE 21
- Исходящий HTTPS-доступ к `api.telegram.org`
- Telegram-бот, созданный через BotFather
- Приватная группа администраторов в Telegram
- Gradle Wrapper из репозитория; глобальная установка Gradle не требуется

Перед продолжением проверьте версию Java:

```text
java -version
```

Основная версия Java должна быть 21.

## 1. Настройка Telegram

1. Создайте бота через BotFather и храните его токен в секрете.
2. Добавьте бота в приватную группу администраторов.
3. Отправьте в этой группе команду с упоминанием бота, например `/start@your_bot_name`.
4. Получите числовой ID группы из ответа `getUpdates`. ID супергрупп Telegram обычно отрицательные.
5. Получите неизменяемый числовой Telegram ID каждого модератора. Не используйте имена пользователей для авторизации.

Например, после сохранения токена в переменной окружения PowerShell может показать последние обновления без указания токена непосредственно в команде:

```powershell
$env:TELEGRAM_BOT_TOKEN = '<bot-token>'
(Invoke-RestMethod "https://api.telegram.org/bot$env:TELEGRAM_BOT_TOKEN/getUpdates").result | ConvertTo-Json -Depth 10
```

Для группы администраторов найдите `message.chat.id`, а для модератора — `message.from.id`. Не публикуйте этот ответ в открытых задачах или чатах: он содержит идентификаторы Telegram и содержимое сообщений.

## 2. Настройка переменных окружения

Приложение читает конфигурацию непосредственно из переменных окружения процесса. Файлы `.env` автоматически не загружаются.

| Переменная | Обязательна | Описание |
| --- | --- | --- |
| `TELEGRAM_BOT_TOKEN` | Да | Секретный токен, выданный BotFather. |
| `TELEGRAM_ADMIN_CHAT_ID` | Да | Числовой ID приватной группы администраторов. |
| `MODERATOR_TELEGRAM_IDS` | Да | Разделенные запятыми числовые Telegram ID пользователей, которым разрешено принимать решения по заявкам. |
| `AUTH_DATABASE_PATH` | Нет | Путь к SQLite; значение по умолчанию — `./data/auth-backend.db`. |
| `HOW_TO_PLAY_URL` | Да | Абсолютный HTTPS-адрес подробной инструкции сервера. |
| `HTTP_BIND_ADDRESS` | Нет | Локальный адрес; по умолчанию `127.0.0.1`. Внешние адреса отклоняются. |
| `HTTP_PORT` | Нет | Локальный HTTP-порт; по умолчанию `8080`. |
| `AUTH_API_TOKEN` | Да | Случайный секрет API сервера длиной не менее 32 символов. |
| `SERVER_ID` | Да | Стабильный идентификатор сервера, например `survival-main`. |
| `ADMIN_LOCALE` | Нет | Язык общей группы администраторов: `en` или `ru`; по умолчанию `en`. |
| `DEFAULT_USER_LOCALE` | Нет | Начальный язык пользователя, если Telegram не предложил поддерживаемый; `en` или `ru`, по умолчанию `en`. |

Никогда не добавляйте настоящие значения в репозиторий. Убедитесь, что учетная запись службы может записывать данные в каталог базы данных.

## 3. Сборка и тестирование

Выполняйте команды из корня репозитория.

Windows PowerShell:

```powershell
.\gradlew.bat :auth-backend:test
.\gradlew.bat :auth-backend:installDist
```

Linux:

```bash
./gradlew :auth-backend:test
./gradlew :auth-backend:installDist
```

Готовый к запуску дистрибутив будет создан в `auth-backend/build/install/auth-backend/`.

## 4. Локальный запуск в Windows

Из корня репозитория выполните:

```powershell
$env:TELEGRAM_BOT_TOKEN = '<bot-token>'
$env:TELEGRAM_ADMIN_CHAT_ID = '-1000000000000'
$env:MODERATOR_TELEGRAM_IDS = '123456789,987654321'
$env:AUTH_DATABASE_PATH = '.\data\auth-backend.db'
$env:HOW_TO_PLAY_URL = 'https://t.me/example/1'
$env:HTTP_BIND_ADDRESS = '127.0.0.1'
$env:HTTP_PORT = '8080'
$env:AUTH_API_TOKEN = '<at-least-32-random-characters>'
$env:SERVER_ID = 'survival-main'
$env:ADMIN_LOCALE = 'ru'
$env:DEFAULT_USER_LOCALE = 'en'
.\gradlew.bat :auth-backend:run
```

После выполнения `installDist` также можно запустить готовый скрипт:

```powershell
.\auth-backend\build\install\auth-backend\bin\auth-backend.bat
```

Отправьте боту в личном чате команду `/start`, выберите язык и начните регистрацию через локализованное меню. После заполнения анкеты в группе администраторов должна появиться карточка заявки на языке `ADMIN_LOCALE`.

## 5. Установка на Debian VPS

Установите среду выполнения Java 21 из подходящего для VPS источника пакетов или от выбранного поставщика JDK, затем проверьте ее командой `java -version`. Соберите дистрибутив на доверенном компьютере или непосредственно на VPS и скопируйте содержимое `auth-backend/build/install/auth-backend/` в `/opt/minecraft-auth/`.

Создайте отдельную учетную запись службы и каталог данных:

```bash
sudo useradd --system --home /var/lib/minecraft-auth --shell /usr/sbin/nologin minecraft-auth
sudo install -d -o minecraft-auth -g minecraft-auth -m 0750 /var/lib/minecraft-auth
sudo install -d -o root -g root -m 0755 /opt/minecraft-auth
```

Поместите собранный дистрибутив в `/opt/minecraft-auth/` и сделайте файл запуска исполняемым:

```bash
sudo chmod 0755 /opt/minecraft-auth/bin/auth-backend
sudo chown -R root:root /opt/minecraft-auth
```

От имени `root` создайте файл `/etc/minecraft-auth.env`:

```text
TELEGRAM_BOT_TOKEN=<bot-token>
TELEGRAM_ADMIN_CHAT_ID=-1000000000000
MODERATOR_TELEGRAM_IDS=123456789,987654321
AUTH_DATABASE_PATH=/var/lib/minecraft-auth/auth-backend.db
HOW_TO_PLAY_URL=https://t.me/example/1
HTTP_BIND_ADDRESS=127.0.0.1
HTTP_PORT=8080
AUTH_API_TOKEN=<at-least-32-random-characters>
SERVER_ID=survival-main
ADMIN_LOCALE=ru
DEFAULT_USER_LOCALE=en
```

Ограничьте доступ к файлу с секретами:

```bash
sudo chown root:root /etc/minecraft-auth.env
sudo chmod 0600 /etc/minecraft-auth.env
```

Создайте `/etc/systemd/system/minecraft-auth.service`:

```ini
[Unit]
Description=Minecraft Telegram Authentication Backend
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=minecraft-auth
Group=minecraft-auth
WorkingDirectory=/var/lib/minecraft-auth
EnvironmentFile=/etc/minecraft-auth.env
ExecStart=/opt/minecraft-auth/bin/auth-backend
Restart=on-failure
RestartSec=5s
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadWritePaths=/var/lib/minecraft-auth

[Install]
WantedBy=multi-user.target
```

Перезагрузите конфигурацию systemd и запустите службу:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now minecraft-auth.service
sudo systemctl status minecraft-auth.service
```

Для просмотра очищенного от секретов вывода приложения используйте:

```bash
sudo journalctl -u minecraft-auth.service -f
```

Приложение намеренно не записывает в журнал токены бота и пароли в открытом виде. Несмотря на это, доступ к журналу следует считать административным.

Установка по release-каталогам, обновление через SFTP, автоматический откат при ошибке health check, безопасные backup/restore SQLite, status, logs и cleanup описаны в [`deploy/vps/README.md`](../deploy/vps/README.md) и [`docs/deployment/VPS_MANAGEMENT.md`](../docs/deployment/VPS_MANAGEMENT.md).

## Локализация

Интерфейс Telegram поддерживает английский и русский языки. Перед регистрацией новый пользователь получает выбор языка. Выбор хранится в SQLite и позднее изменяется через `⚙️ Настройки`. Значение Telegram `language_code` используется только как первоначальная подсказка и не заменяет сохранённый выбор.

`ADMIN_LOCALE` независимо задаёт язык общей группы администраторов. Личный чат модератора использует сохранённый язык самого модератора. Числовые Telegram ID, callback-идентификаторы, значения базы данных и внутренние enum не зависят от языка.

Переводы находятся в `src/main/resources/i18n/messages_en.properties` и `messages_ru.properties`. Чтобы добавить язык, добавьте его в `BotLocale`, создайте соответствующий ресурс, переведите каждый обязательный `MessageKey`, расширьте проверку конфигурации и тесты маршрутизации, затем задокументируйте новый код. Английский остаётся резервным языком. Тесты требуют одинаковый набор ключей в английском и русском ресурсах.

## Обновление

1. Остановите службу и создайте резервную копию `/var/lib/minecraft-auth/auth-backend.db`.
2. Соберите новый дистрибутив командой `:auth-backend:installDist`.
3. Замените содержимое `/opt/minecraft-auth/` новым дистрибутивом.
4. Запустите службу и проверьте ее состояние и процесс регистрации через Telegram.

Не удаляйте базу данных SQLite при обновлении. Она является источником истины для заявок, учетных записей, журнала аудита и ожидающих событий синхронизации.

## Устранение неполадок

- `TELEGRAM_BOT_TOKEN is required`: задайте переменную в окружении того же процесса или в файле окружения systemd, из которого запускается бэкенд.
- Карточка заявки не появляется: проверьте отрицательный ID группы администраторов, наличие бота в группе, исходящий HTTPS-доступ и состояние службы.
- Модератор получает ошибку авторизации: добавьте его числовой `from.id` в `MODERATOR_TELEGRAM_IDS` и перезапустите службу.
- SQLite не может открыть базу данных: проверьте, что учетная запись службы владеет родительским каталогом и имеет право записи.
- Бот не отвечает в личном чате: проверьте `journalctl`, убедитесь, что токен принадлежит нужному боту и что другой процесс не использует long polling с тем же токеном.
