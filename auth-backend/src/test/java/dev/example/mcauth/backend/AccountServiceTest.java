package dev.example.mcauth.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccountServiceTest {
    @TempDir Path temporaryDirectory;

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
}
