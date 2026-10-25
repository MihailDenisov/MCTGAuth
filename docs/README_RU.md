# Документация MCTGAuth

[English version](README.md) · [Главная страница проекта](../README_RU.md)

Этот индекс разделяет инструкции оператора и проектные материалы. Текущий release: Java 21, Minecraft 1.21.1, NeoForge 21.1.244 и MCTGAuth 0.6.5.

## Начните здесь

- [Обзор проекта и сборка](../README_RU.md)
- [Backend](../auth-backend/README_RU.md)
- [MCTGAuth Server](../auth-neoforge-server/README_RU.md)
- [MCTGAuth Client](../auth-neoforge-client/README_RU.md)
- [Production-развёртывание и эксплуатация](deployment/DEPLOYMENT_RU.md)
- [Устранение неполадок](troubleshooting/TROUBLESHOOTING_RU.md)
- [Примечания к выпуску 0.6.0](RELEASE_NOTES_0.6.0_RU.md)
- [Переход с 0.5.0 на 0.6.0](MIGRATION_0.5.0_TO_0.6.0_RU.md)
- [Примечания к выпуску 0.6.1](RELEASE_NOTES_0.6.1_RU.md)
- [Переход с 0.6.0 на 0.6.1](MIGRATION_0.6.0_TO_0.6.1_RU.md)
- [Примечания к выпуску 0.6.2](RELEASE_NOTES_0.6.2_RU.md)
- [Переход с 0.6.1 на 0.6.2](MIGRATION_0.6.1_TO_0.6.2_RU.md)
- [Примечания к выпуску 0.6.3](RELEASE_NOTES_0.6.3_RU.md)
- [Переход с 0.6.2 на 0.6.3](MIGRATION_0.6.2_TO_0.6.3_RU.md)
- [Примечания к выпуску 0.6.4](RELEASE_NOTES_0.6.4_RU.md)
- [Переход с 0.6.3 на 0.6.4](MIGRATION_0.6.3_TO_0.6.4_RU.md)
- [Примечания к выпуску 0.6.5](RELEASE_NOTES_0.6.5_RU.md)
- [Переход с 0.6.4 на 0.6.5](MIGRATION_0.6.4_TO_0.6.5_RU.md)
- [API телеметрии](api/TELEMETRY_API_RU.md)
- [Примечания к выпуску 0.4.2](RELEASE_NOTES_0.4.2_RU.md)

## Архитектура и безопасность

- [Логика проекта](PROJECT_LOGIC.md) — ответственность, workflows, синхронизация и поведение при сбоях.
- [Структура проекта](PROJECT_STRUCTURE.md) — зависимости модулей и основные пакеты.
- [Модель безопасности](architecture/SECURITY_MODEL.md) — учётные данные, токены, авторизация и deployment-контроли.
- [Безопасность удалённых команд](architecture/REMOTE_COMMAND_SECURITY.md) — типизированный allowlist, expiry и защита от повторов.
- [Архитектура Minecraft-авторизации](MINECRAFT_AUTH_ARCHITECTURE.md)

## Интерфейсы и workflows

- [Протокол авторизации V1](AUTH_PROTOCOL.md)
- [Поток авторизации](MCTGAUTH_AUTH_FLOW.md)
- [HTTP API синхронизации](api/SYNC_API.md)
- [HTTP API удалённых команд](api/REMOTE_COMMAND_API.md)
- [Модель БД и миграции](database/SCHEMA.md)
- [Регистрация](flows/REGISTRATION.md), [смена пароля](flows/PASSWORD_CHANGE.md) и [обращения](flows/TICKETS.md)
- [Меню пользователя](telegram/USER_MENU.md), [меню администратора](telegram/ADMIN_MENU.md) и [интерфейс обращений](telegram/TICKET_FLOWS.md)

## Установка и release-операции

- [Инструкция по развёртыванию](deployment/DEPLOYMENT_RU.md) — основной управляемый release-процесс.
- [Технические сведения о VPS toolkit](deployment/VPS_MANAGEMENT.md) описывают структуру и транзакции.
- [Развёртывание сервера](NEOFORGE_SERVER_DEPLOYMENT.md) и [установка клиента](NEOFORGE_CLIENT_INSTALLATION.md) остаются дополнительными специализированными инструкциями.

Планируемые и неподдерживаемые функции отмечаются явно. В частности, `RESYNC_PLAYER` теперь возвращает структурированную диагностику и запрашивает немедленный poll, но sync API всё ещё не поддерживает snapshot одного аккаунта; безопасная live-перезагрузка конфигурации также пока не работает.
