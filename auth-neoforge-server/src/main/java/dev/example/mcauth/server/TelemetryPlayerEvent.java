package dev.example.mcauth.server;

import java.time.Instant;
import java.util.UUID;

record TelemetryPlayerEvent(UUID eventId, String serverId, UUID serverInstanceId, String type,
                            long minecraftAccountId, String nickname, Instant occurredAt) { }
