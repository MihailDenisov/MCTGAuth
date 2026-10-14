# MCTGAuth Client Installation

Install Java 21, Minecraft 1.21.1, NeoForge 21.1.244 or a compatible 21.1.x release, and `mctgauth-client-0.6.4.jar` in the client's `mods/` folder.

Open **MCTGAuth** on the title screen or **Mods → MCTGAuth Client → Config**, paste the password issued by the Telegram bot, and save. The password is stored as plaintext in `.minecraft/config/mctgauth-client.json`; protect the Minecraft configuration folder. With Automatic Authentication enabled, later joins to an MCTGAuth-enabled server complete without an interactive login screen. The client remains passive in singleplayer and on servers without MCTGAuth, and it does not derive or send authentication material until that server advertises MCTGAuth support.

If no password is stored, or a stored password is rejected after a backend password rotation, the same settings screen opens with **Save and authenticate** and **Disconnect** actions.
