---
name: project-architecture
description: Use when adding features or deciding which module owns code in the Minecraft Telegram Auth repository.
---
# Project Architecture

Read `docs/PROJECT_STRUCTURE.md` and `docs/PROJECT_LOGIC.md` first.

Keep `auth-api` free of infrastructure dependencies. Put Telegram/HTTP/backend persistence in `auth-backend`; Minecraft server enforcement/sync consumption in `auth-neoforge-server`; client UI/credential handling in `auth-neoforge-client`.

Before coding, state the owning module, data it reads/writes, interfaces crossing module boundaries, failure behavior when the VPS is unavailable, and tests required. Reject shortcuts that make Minecraft read the VPS SQLite file or make the backend depend on NeoForge classes.
