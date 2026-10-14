package dev.example.mcauth.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountServiceTest {
    @TempDir Path temporaryDirectory;

    @Test
    void chatTagSelectionEnforcesOwnershipEnabledStateAndClearsWithAccountDisable() throws Exception {
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("chat-tag.db"))) {
            long ownerId = 3010L;
            long ownAccountId = context.register(ownerId, "TagOwner");
            long otherAccountId = context.register(3011L, "OtherOwner");
            ModeratorIdentity moderator = new ModeratorIdentity(BackendTestContext.MODERATOR_ID, "mod");

            ChatTagPreference initial = context.accounts.chatTagPreference(ownerId);
            assertEquals(ownerId, initial.telegramUserId());
            assertEquals(null, initial.accountId());
            assertEquals(null, initial.minecraftName());

            ChatTagPreference selected = context.accounts.selectChatTag(ownerId, ownAccountId);
            assertEquals(ownAccountId, selected.accountId());
            assertEquals("TagOwner", selected.minecraftName());

            WorkflowException otherOwner = assertThrows(WorkflowException.class,
                    () -> context.accounts.selectChatTag(ownerId, otherAccountId));
            assertEquals(WorkflowException.Code.NOT_FOUND, otherOwner.code());
            assertEquals(ownAccountId, context.accounts.chatTagPreference(ownerId).accountId());

            context.accounts.disable(moderator, ownAccountId, "test disable");
            assertEquals(null, context.accounts.chatTagPreference(ownerId).accountId());
            WorkflowException disabled = assertThrows(WorkflowException.class,
                    () -> context.accounts.selectChatTag(ownerId, ownAccountId));
            assertEquals(WorkflowException.Code.INVALID_STATE, disabled.code());

            context.accounts.enable(moderator, ownAccountId, "test enable");
            context.accounts.selectChatTag(ownerId, ownAccountId);
            ChatTagPreference cleared = context.accounts.selectChatTag(ownerId, null);
            assertEquals(null, cleared.accountId());
            assertEquals(null, cleared.minecraftName());
            assertTrue(context.accounts.chatTagPreferences().stream()
                    .anyMatch(preference -> preference.telegramUserId() == ownerId && !preference.hasTag()));
        }
    }

    @Test
    void passwordChangeIncrementsVersionAndIsRateLimited() throws Exception {
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("password.db"))) {
            long accountId = context.register(3001L, "PasswordUser");
            try (CredentialIssueResult result = context.accounts.changePassword(3001L, accountId)) {
                assertEquals(2, result.credentialVersion());
            }
            assertEquals(2, context.accounts.accountsFor(3001L).getFirst().credentialVersion());
            WorkflowException unauthorized = assertThrows(WorkflowException.class,
                    () -> context.accounts.changePassword(9999L, accountId));
            assertEquals(WorkflowException.Code.NOT_FOUND, unauthorized.code());
            WorkflowException limited = assertThrows(WorkflowException.class,
                    () -> context.accounts.changePassword(3001L, accountId));
            assertEquals(WorkflowException.Code.RATE_LIMITED, limited.code());
        }
    }

    @Test
    void manualAccountCreationRequiresModeratorAndIsAudited() throws Exception {
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("manual.db"))) {
            context.accounts.touchTelegramUser(3002L, "owner");
            assertThrows(WorkflowException.class, () -> context.accounts.createManual(
                    new ModeratorIdentity(9999L, "intruder"), 3002L, "TechAccount", false));
            try (CredentialIssueResult result = context.accounts.createManual(
                    new ModeratorIdentity(BackendTestContext.MODERATOR_ID, "mod"),
                    3002L, "TechAccount", false)) {
                assertEquals("TechAccount", result.nickname());
            }
            long auditCount = context.database.transaction(connection -> {
                try (var statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM audit_log WHERE event_type = 'MINECRAFT_ACCOUNT_CREATED_MANUALLY'")) {
                    try (var result = statement.executeQuery()) { result.next(); return result.getLong(1); }
                }
            });
            assertEquals(1, auditCount);
        }
    }

    @Test
    void moderatorEnableDisableIsAuthorizedAuditedIdempotentAndAccountSpecific() throws Exception {
        try (BackendTestContext context = new BackendTestContext(temporaryDirectory.resolve("state.db"))) {
            long ownerId = 3003L;
            long mainId = context.register(ownerId, "MainNick");
            long altId;
            try (CredentialIssueResult result = context.accounts.createManual(
                    new ModeratorIdentity(BackendTestContext.MODERATOR_ID, "mod"),
                    ownerId, "AltNick", false)) {
                altId = result.accountId();
            }
            ModeratorIdentity moderator = new ModeratorIdentity(BackendTestContext.MODERATOR_ID, "mod");

            assertThrows(WorkflowException.class, () -> context.accounts.disable(
                    new ModeratorIdentity(9999L, "intruder"), altId, "unauthorized"));
            AccountStateChangeResult disabled = context.accounts.disable(moderator, altId, "Rule violation");
            assertTrue(disabled.changed());
            assertFalse(disabled.account().enabled());
            assertTrue(context.accounts.accountsFor(ownerId).stream()
                    .filter(account -> account.id() == mainId).findFirst().orElseThrow().enabled());

            AccountStateChangeResult duplicate = context.accounts.disable(moderator, altId, "duplicate");
            assertFalse(duplicate.changed());
            AccountStateChangeResult enabled = context.accounts.enable(moderator, altId, "restored");
            assertTrue(enabled.changed());
            assertTrue(enabled.account().enabled());

            long disabledEvents = count(context, "sync_events", "event_type = 'ACCOUNT_DISABLED'");
            long enabledEvents = count(context, "sync_events", "event_type = 'ACCOUNT_ENABLED'");
            long disabledAudits = count(context, "audit_log", "event_type = 'ACCOUNT_DISABLED'");
            assertEquals(1, disabledEvents);
            assertEquals(1, enabledEvents);
            assertEquals(1, disabledAudits);
            String details = context.database.transaction(connection -> {
                try (var statement = connection.prepareStatement(
                        "SELECT details_json FROM audit_log WHERE event_type = 'ACCOUNT_DISABLED'")) {
                    try (var result = statement.executeQuery()) { result.next(); return result.getString(1); }
                }
            });
            assertTrue(details.contains("Rule violation"));
            assertTrue(details.contains("AltNick"));
            assertTrue(details.contains("\"previousEnabled\":true"));
            assertTrue(details.contains("\"newEnabled\":false"));
            assertEquals(altId, context.accounts.search(moderator, "altnick").getFirst().id());
        }
    }

    private static long count(BackendTestContext context, String table, String predicate) {
        return context.database.transaction(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM " + table + " WHERE " + predicate);
                 var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        });
    }
}
