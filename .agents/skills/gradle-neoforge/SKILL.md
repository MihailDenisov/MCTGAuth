---
name: gradle-neoforge
description: Use when bootstrapping Gradle, VS Code, Java 21, or NeoForge 1.21.1 build/run configuration.
---
# Gradle and NeoForge Bootstrap

Use JDK 21. Treat the official NeoForge 1.21.1 MDK/documentation as the source of truth for plugin IDs, plugin versions, mappings/runs, and `neoforge.mods.toml` metadata. Pin versions after verification.

Keep root Gradle configuration shared where safe, but allow NeoForge modules to follow MDK-specific configuration. Verify with wrapper commands: `./gradlew build`, then dedicated `runClient`/`runServer` tasks once configured. Test server-side code in a dedicated-server run.
