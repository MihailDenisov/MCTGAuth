# MCTGAuth 0.5.0

[English version](RELEASE_NOTES_0.5.0.md)

## Главное

- Оператор сервера может настраивать Telegram-контент и необязательные функции в несекретном UTF-8 YAML.
- Отключение аккаунта теперь имеет полный audit-контекст, а sync подтверждается только после надёжной записи cache.
- Защищённая операция полного сброса согласованно очищает identity-данные backend и Minecraft auth cache после проверенных backup.

## Telegram

- Локализованные `howToPlay.en` и `howToPlay.ru` загружаются при старте backend.
- Необязательные ссылки на карту и модпак появляются только при включении и корректном HTTPS URL.
- Необязательный subscription gate вызывает Telegram `getChatMember` до создания состояния мастера регистрации.
- Неподписанный пользователь получает кнопки подписки и повторной проверки. Временная ошибка API допускает retry и fail-closed блокирует регистрацию.
- При выключенных функциях сохраняется прежнее меню; устаревшие callbacks безопасно отклоняются.

## Администрирование

- Audit активации и деактивации аккаунта содержит состояние до и после операции.
- Авторизация по-прежнему использует числовой Telegram ID; username остаётся отображаемыми метаданными.
- Full reset сохраняет schema migrations, модераторов и их permissions, а также SQLite ID sequences, но удаляет operational identity, credential, ticket, sync, command и audit data.

## Minecraft

- Sync event подтверждается только после успешной записи account cache.
- Зафиксированный `ACCOUNT_DISABLED` отключает соответствующего онлайн-игрока при `disconnectPlayerWhenAccountDisabled=true`.
- Ранее синхронизированные аккаунты продолжают входить при недоступности backend или Telegram.
- Wire-протокол аутентификации остаётся версией `1`; формат payload в 0.5.0 не изменён.

## Эксплуатация

- `deploy/vps/reset-database.sh` поддерживает считающий `--dry-run`, точное подтверждение, координацию служб, backup SQLite/cache, post-reset integrity checks и безопасное поведение при ошибке.
- Несекретный feature-контент хранится в `/etc/minecraft-auth-telegram.yaml`; `/etc/minecraft-auth.env` указывает на файл через `TELEGRAM_FEATURE_CONFIG_PATH`.
- Backend-БД остаётся на schema `4`; миграция SQL или Minecraft cache не требуется.

## Переход

Следуйте инструкции [Переход с 0.4.x на 0.5.0](MIGRATION_0.4.x_TO_0.5.0_RU.md). Обычное обновление сохраняет все аккаунты и credentials. **FULL RESET не является шагом обновления**: он делает все прежние пароли игроков недействительными и требует повторной регистрации каждого игрока.

## Файлы

- `mctgauth-client-0.5.0.jar` — только для клиентов игроков.
- `mctgauth-server-0.5.0.jar` — только для dedicated server.
- `auth-backend-0.5.0.tar.gz` — для загрузки на VPS и установки через `update.sh`.
- `SHA256SUMS.txt` — SHA-256 суммы трёх распространяемых артефактов.

## Контрольные суммы

Перед установкой сверьте каждый загруженный файл с приложенным `SHA256SUMS.txt`. В `release.properties` архива записан точный Git commit выпуска.
