# Примечания к выпуску MCTGAuth 0.4.2

[English version](RELEASE_NOTES_0.4.2.md)

## Исправление активации клиента

- Теперь каждое Minecraft-подключение MCTGAuth Client начинает в пассивном режиме.
- Одиночное подключение к integrated server определяется по внутреннему in-memory соединению Minecraft и не может активировать MCTGAuth.
- Сторонние серверы без MCTGAuth не затрагиваются; отсутствие `server_hello` не считается ошибкой или тайм-аутом.
- MCTGAuth Server теперь первым объявляет `server_hello`, а клиент только после этого отправляет `client_hello`.
- Payload-регистрации клиента необязательны, а регистрации MCTGAuth Server остаются обязательными; серверная авторизация продолжает работать по fail-closed принципу.
- Экран пароля и Argon2id могут запускаться только после активации capability и получения корректного challenge.
- Поколения подключений по-прежнему блокируют запоздавшую отправку proof после отключения или смены сервера.

Структуры wire payload и `AUTH_PROTOCOL_VERSION=1` не изменились.

Клиент и сервер 0.4.2 следует обновлять вместе. Клиент 0.4.2 намеренно ждёт первого объявления capability сервером, а сервер 0.4.1 отправляет его только после прежнего client-first hello.

## Артефакты

```text
auth-backend/build/distributions/auth-backend-0.4.2.tar.gz
auth-neoforge-server/build/libs/mctgauth-server-0.4.2.jar
auth-neoforge-client/build/libs/mctgauth-client-0.4.2.jar
```
