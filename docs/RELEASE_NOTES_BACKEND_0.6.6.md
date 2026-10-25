# MCTGAuth Backend 0.6.6

[Русская версия](RELEASE_NOTES_BACKEND_0.6.6_RU.md)

## Compatibility

| Component | Supported version |
| --- | --- |
| Backend | 0.6.6 |
| Client mod | 0.6.5 |
| Server mod | 0.6.5 |
| Authentication protocol | 2 |
| Backend database schema | 8 |
| Fingerprint format | 1 |

This backend-only release does not require replacing the 0.6.5 Minecraft mods. It must be installed after the complete 0.6.5 full-stack upgrade. Client/server 0.6.4 remain unsupported because they do not implement protocol 2 and device fingerprints.

## Telegram UI

- User menus, settings, account/playtime views, Server Information, registration/report wizards, ticket navigation, Back, confirmations, and pagination reuse one active message per numeric user and chat.
- `/adm` maintains an independent active message per moderator and chat. User and administration sessions do not replace each other.
- Request lists, search, statistics, account controls, and device/ban/exemption screens use the same admin projection.
- A recoverable edit failure creates exactly one replacement. `message is not modified` is treated as successful and creates no replacement.
- Navigation callbacks are bound to the numeric Telegram owner of the navigation message. Every mutation still reloads and validates authoritative database state.
- Successfully processed temporary moderator input is deleted when Telegram permits it; deletion failure does not fail the workflow.

## Persistent and sensitive messages

Generated or changed passwords are standalone HTML messages without navigation markup. Their message IDs are never stored in UI sessions and later navigation cannot overwrite them.

Registration/ticket completion notices, ticket replies and closure/rejection results remain standalone. Ticket history and all moderation state remain durable in SQLite; Telegram messages are projections only.

## Unchanged boundaries

There is no V009 migration, protocol change, fingerprint change, authentication change, or Minecraft-module change. Backend unavailability behavior and the 0.6.5 device/ban policy are unchanged.
