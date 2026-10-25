package dev.example.mcauth.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.example.mcauth.api.SyncEvent;
import dev.example.mcauth.protocol.Argon2Verifier;
import dev.example.mcauth.protocol.AuthProtocol;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public final class SyncEventProcessor {
    private final AuthAccountStore store;

    public SyncEventProcessor(AuthAccountStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public ApplyResult apply(SyncEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.schemaVersion() != 1) throw new IllegalArgumentException("Unsupported sync schema version");
        JsonObject payload;
        try {
            payload = JsonParser.parseString(event.payloadJson()).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Malformed synchronization payload", exception);
        }
        if (event.type() == dev.example.mcauth.api.SyncEventType.DEVICE_BANNED
                || event.type() == dev.example.mcauth.api.SyncEventType.DEVICE_UNBANNED) {
            long deviceId = positiveLong(payload, "deviceId");
            AuthAccountStore.SyncApplyResult deviceResult = store.applyDeviceBanEvent(event.id(), event.type(),
                    Instant.now(), deviceId, event.type() == dev.example.mcauth.api.SyncEventType.DEVICE_BANNED);
            return deviceResult == AuthAccountStore.SyncApplyResult.APPLIED
                    ? ApplyResult.APPLIED : ApplyResult.ALREADY_PROCESSED;
        }
        AuthAccountStore.SyncApplyResult result = store.applySyncEvent(event.id(), event.type(), Instant.now(), accounts -> {
            switch (event.type()) {
                case ACCOUNT_CREATED -> upsertAccount(accounts, payload, event.createdAt());
                case CREDENTIAL_CHANGED -> updateCredential(accounts, payload, event.createdAt());
                case ACCOUNT_ENABLED -> setEnabled(accounts, payload, true, event.createdAt());
                case ACCOUNT_DISABLED, ACCOUNT_REMOVED -> setEnabled(accounts, payload, false, event.createdAt());
                case USER_BANNED -> setBanned(accounts, payload, true, event.createdAt());
                case USER_UNBANNED -> setBanned(accounts, payload, false, event.createdAt());
                case DEVICE_BANNED, DEVICE_UNBANNED -> throw new IllegalStateException("Device event routed incorrectly");
            }
        });
        return result == AuthAccountStore.SyncApplyResult.APPLIED ? ApplyResult.APPLIED : ApplyResult.ALREADY_PROCESSED;
    }

    private static void upsertAccount(Map<String, AuthAccount> accounts, JsonObject payload, Instant eventTime) {
        long accountId = positiveLong(payload, "accountId");
        String name = text(payload, "nickname", 16);
        String normalized = text(payload, "normalizedNickname", 16);
        if (!AuthProtocol.normalizeMinecraftName(name).equals(normalized)) {
            throw new IllegalArgumentException("Invalid normalized account identity");
        }
        int version = positiveInt(payload, "credentialVersion");
        String verifier = verifier(payload);
        AuthAccount existingById = findByAccountId(accounts, accountId);
        AuthAccount existingByName = accounts.get(normalized);
        if (existingByName != null && existingByName.accountId() != accountId) {
            throw new IllegalStateException("Account name is already assigned locally");
        }
        if (existingById != null && version < existingById.credentialVersion()) return;
        if (existingById != null && !existingById.normalizedName().equals(normalized)) {
            accounts.remove(existingById.normalizedName());
        }
        accounts.put(normalized, new AuthAccount(accountId, name, normalized, null, verifier, version,
                bool(payload, "enabled"), bool(payload, "licensed"), eventTime));
    }

    private static void updateCredential(Map<String, AuthAccount> accounts, JsonObject payload, Instant eventTime) {
        long accountId = positiveLong(payload, "accountId");
        AuthAccount current = requireAccount(accounts, accountId);
        int version = positiveInt(payload, "credentialVersion");
        String verifier = verifier(payload);
        if (version < current.credentialVersion() || eventTime.isBefore(current.updatedAt())) return;
        if (version == current.credentialVersion() && !verifier.equals(current.credentialVerifier())) {
            throw new IllegalStateException("Credential version was reused with different verifier material");
        }
        accounts.put(current.normalizedName(), new AuthAccount(current.accountId(), current.minecraftName(),
                current.normalizedName(), current.minecraftUuid(), verifier, version, bool(payload, "enabled"),
                current.banned(), current.licensed(), eventTime));
    }

    private static void setEnabled(Map<String, AuthAccount> accounts, JsonObject payload, boolean enabled,
                                   Instant eventTime) {
        AuthAccount current = requireAccount(accounts, positiveLong(payload, "accountId"));
        if (eventTime.isBefore(current.updatedAt())) return;
        accounts.put(current.normalizedName(), new AuthAccount(current.accountId(), current.minecraftName(),
                current.normalizedName(), current.minecraftUuid(), current.credentialVerifier(),
                current.credentialVersion(), enabled, current.banned(), current.licensed(), eventTime));
    }

    private static void setBanned(Map<String, AuthAccount> accounts, JsonObject payload, boolean banned,
                                  Instant eventTime) {
        AuthAccount current = requireAccount(accounts, positiveLong(payload, "accountId"));
        if (eventTime.isBefore(current.updatedAt())) return;
        accounts.put(current.normalizedName(), new AuthAccount(current.accountId(), current.minecraftName(),
                current.normalizedName(), current.minecraftUuid(), current.credentialVerifier(),
                current.credentialVersion(), current.enabled(), banned, current.licensed(), eventTime));
    }

    private static AuthAccount requireAccount(Map<String, AuthAccount> accounts, long accountId) {
        AuthAccount account = findByAccountId(accounts, accountId);
        if (account == null) throw new IllegalStateException("Sync event references an unknown account");
        return account;
    }

    private static AuthAccount findByAccountId(Map<String, AuthAccount> accounts, long accountId) {
        return accounts.values().stream().filter(account -> account.accountId() == accountId).findFirst().orElse(null);
    }

    private static String verifier(JsonObject payload) {
        String encoded = text(payload, "credentialVerifier", 512);
        try (Argon2Verifier ignored = Argon2Verifier.parse(encoded)) {
            return encoded;
        }
    }

    private static String text(JsonObject payload, String field, int maxLength) {
        if (!payload.has(field) || !payload.get(field).isJsonPrimitive()
                || !payload.getAsJsonPrimitive(field).isString()) throw new IllegalArgumentException("Invalid sync field: " + field);
        String value = payload.get(field).getAsString();
        if (value.isBlank() || value.length() > maxLength) throw new IllegalArgumentException("Invalid sync field: " + field);
        return value;
    }

    private static long positiveLong(JsonObject payload, String field) {
        try {
            long value = payload.get(field).getAsLong();
            if (value <= 0) throw new IllegalArgumentException("Invalid sync field: " + field);
            return value;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid sync field: " + field, exception);
        }
    }

    private static int positiveInt(JsonObject payload, String field) {
        long value = positiveLong(payload, field);
        if (value > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid sync field: " + field);
        return (int) value;
    }

    private static boolean bool(JsonObject payload, String field) {
        if (!payload.has(field) || !payload.get(field).isJsonPrimitive()
                || !payload.getAsJsonPrimitive(field).isBoolean()) throw new IllegalArgumentException("Invalid sync field: " + field);
        return payload.get(field).getAsBoolean();
    }

    public enum ApplyResult { APPLIED, ALREADY_PROCESSED }
}
