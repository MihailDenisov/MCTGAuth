package dev.example.mcauth.backend;

import java.time.Instant;
import java.util.Objects;

public record AccountStateChangeResult(AccountView account, boolean changed, Instant occurredAt) {
    public AccountStateChangeResult {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
