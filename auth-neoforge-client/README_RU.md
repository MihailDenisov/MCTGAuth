# MCTGAuth Client

Клиентский мод NeoForge 1.21.1 для сервера MCTGAuth. Требуется Java 21, Minecraft 1.21.1 и NeoForge 21.1.244 (либо совместимая версия 1.21.x). Установите только `build/libs/mctgauth-client-0.4.1.jar` в `mods/` игрока.

Клиент хранит выданный Telegram пароль в `.minecraft/config/mctgauth-client.json` в открытом виде — это решение продукта. Защитите папку конфигурации и не передавайте её другим. Это единственное разрешённое место открытого пароля: он не отправляется backend, не попадает в диагностику и не передаётся по Minecraft-протоколу. Argon2id вычисляется вне render-потока, отправляется только nonce-связанный proof HMAC-SHA256.

## Настройки и аутентификация

Откройте **MCTGAuth** кнопкой титульного экрана или через **Mods → MCTGAuth Client → Config**. Доступны ввод, изменение и удаление пароля, **Show Password** и **Automatic Authentication**. При отказе сохранённого пароля клиент попросит новый. Удаление локального значения не меняет пароль аккаунта в backend.

Клиент и сервер согласуют протокол версии 1. Отсутствие мода, несовпадение протокола, неверные данные, превышение попыток, тайм-аут, отключённый/несинхронизированный аккаунт или внутренняя ошибка вызывают отказ/отключение. Локальный серверный кэш позволяет входить без backend, но клиентский мод и корректный пароль обязательны.

## Сборка и устранение проблем

```powershell
.\gradlew.bat :auth-neoforge-client:test
.\gradlew.bat :auth-neoforge-client:build
.\gradlew.bat :auth-neoforge-client:runClient
```

Подробнее: [`docs/NEOFORGE_CLIENT_INSTALLATION.md`](../docs/NEOFORGE_CLIENT_INSTALLATION.md), [`docs/MCTGAUTH_CLIENT.md`](../docs/MCTGAUTH_CLIENT.md), [`docs/AUTH_PROTOCOL.md`](../docs/AUTH_PROTOCOL.md). AutoModPack — необязательное средство доставки; он не заменяет установку мода и защиту локального файла.
