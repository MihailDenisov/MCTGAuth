# Переход с MCTGAuth 0.4.x на 0.5.0

[English version](MIGRATION_0.4.x_TO_0.5.0.md)

## Обзор

MCTGAuth 0.5.0 обновляется поверх 0.4.1 или 0.4.2. Версия протокола аутентификации остаётся `1`, схема backend-БД — `4`, формат Minecraft cache — schema `1`. Существующие синхронизированные аккаунты не требуют сброса пароля и продолжают работать при недоступности backend.

Запланируйте короткий перезапуск backend и обычный перезапуск Minecraft-сервера для замены серверного JAR. После замены клиентского JAR игроки должны перезапустить клиент. Рекомендуется одновременно обновить все три компонента, хотя wire-протокол не изменился.

## 1. Резервные копии

На VPS создайте проверенный SQLite backup и сохраните конфигурацию:

```bash
sudo /opt/minecraft-auth-tools/backup.sh
sudo cp -a /etc/minecraft-auth.env /etc/minecraft-auth.env.pre-0.5.0
sudo cp -a /etc/minecraft-auth-telegram.yaml /etc/minecraft-auth-telegram.yaml.pre-0.5.0
```

Backup БД записывается в `/var/backups/minecraft-auth/auth-backend-<UTC timestamp>.db`. Не копируйте активный SQLite-файл напрямую.

Перед заменой серверного мода остановите настроенную службу Minecraft и сохраните cache. Подставьте абсолютный `MINECRAFT_GAME_DIR` из `/etc/minecraft-auth.env`:

```bash
sudo systemctl stop minecraft.service
sudo tar -C <MINECRAFT_GAME_DIR> -czf /var/backups/minecraft-auth/mctgauth-cache-pre-0.5.0.tar.gz mctgauth
```

Если `MINECRAFT_SERVICE_NAME` отличается от `minecraft.service`, используйте настроенное значение.

## 2. Конфигурация функций Telegram

Installer создаёт `/etc/minecraft-auth-telegram.yaml` только при отсутствии файла и добавляет:

```text
TELEGRAM_FEATURE_CONFIG_PATH=/etc/minecraft-auth-telegram.yaml
```

Для существующей установки скопируйте `deploy/vps/telegram-features.example.yaml`, задайте владельца `root:minecraft-auth` и права `0640`, затем добавьте переменную окружения, не заменяя существующие секреты.

Поддерживаются ключи:

```yaml
howToPlay:
  en: |
    English instructions.
  ru: |
    Инструкция на русском языке.
registration:
  subscriptionCheck:
    enabled: false
    channel: "@example_channel"
    channelUrl: "https://t.me/example_channel"
serverMap:
  enabled: false
  url: "https://map.example.com/"
modpackDownload:
  enabled: false
  url: "https://t.me/example_channel/123"
```

Все необязательные функции по умолчанию выключены. Некорректные необязательные URL скрываются с предупреждением, встроенный текст «Как играть» остаётся fallback. Конфигурация читается только при запуске backend.

Если `registration.subscriptionCheck.enabled: true`, назначьте бота администратором указанного канала, чтобы Telegram гарантированно разрешал `getChatMember` для других пользователей. `channel` и `channelUrl` должны указывать на один канал. При ошибке Telegram API новая регистрация fail-closed предлагает повторить проверку, но не объявляет пользователя неподписанным.

## 3. Установка 0.5.0

Загрузите `auth-backend-0.5.0.tar.gz`, сверьте его с `SHA256SUMS.txt` и выполните:

```bash
sudo /opt/minecraft-auth-tools/update.sh /path/to/auth-backend-0.5.0.tar.gz
sudo /opt/minecraft-auth-tools/status.sh
```

`update.sh` сам создаёт SQLite backup, устанавливает неизменяемый каталог release, атомарно переключает symlink и автоматически возвращает приложение назад при неуспешном health check. Новая SQL-миграция в 0.5.0 отсутствует: schema `4` остаётся schema `4`.

Замените старый серверный мод на `mctgauth-server-0.5.0.jar`, сохраните `config/mctgauth-server.toml` и проверьте:

```toml
[sync]
disconnectPlayerWhenAccountDisabled = true
```

Запустите Minecraft-службу и раздайте игрокам `mctgauth-client-0.5.0.jar`. Не устанавливайте server JAR клиентам и client JAR на dedicated server.

## 4. Проверка

- `/health` сообщает `"status":"ok"` и schema version `4`.
- `/version` сообщает application version `0.5.0` и ожидаемый commit.
- В `journalctl -u minecraft-auth.service` нет ошибок feature-config или Telegram authorization.
- `/menu` показывает только настроенные ссылки и локализованный текст «Как играть».
- Тестовая регистрация проходит subscription gate только после того, как бот видит подписку.
- Отключение тестового аккаунта создаёт audit с изменением состояния, синхронизируется и отключает онлайн-игрока при включённой server-настройке.
- Ранее синхронизированный активный аккаунт продолжает входить при временно остановленном backend.

## Откат

Так как форматы schema и cache не изменились, поддерживается откат приложения на 0.4.2:

```bash
sudo /opt/minecraft-auth-tools/versions.sh
sudo /opt/minecraft-auth-tools/rollback.sh <каталог-release-0.4.2>
```

Одновременно верните предыдущие client/server JAR. Откат приложения не восстанавливает БД. Восстанавливайте БД и соответствующий Minecraft cache только для восстановления данных и при остановленных службах; никогда не восстанавливайте одну сторону отдельно.

## Full reset не является шагом обновления

`sudo /opt/minecraft-auth-tools/reset-database.sh` — необязательная разрушительная операция. Она удаляет пользователей, регистрации, аккаунты, verifier, тикеты, сообщения, sync events, remote commands и audit history, а также очищает Minecraft auth cache. Все игроки должны зарегистрироваться заново. Сначала используйте `--dry-run`; точное подтверждение вводите только при намеренном полном сбросе.
