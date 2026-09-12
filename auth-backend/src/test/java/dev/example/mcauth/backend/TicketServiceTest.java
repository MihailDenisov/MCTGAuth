package dev.example.mcauth.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketServiceTest {
    private static final long MODERATOR_ID = 1001L;
    private static final long OTHER_MODERATOR_ID = 1002L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-11T10:15:30Z"), ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    private BackendDatabase database;
    private TicketService tickets;

    @BeforeEach
    void setUp() throws Exception {
        database = BackendDatabase.open(temporaryDirectory.resolve("test.db"));
        ModeratorService moderators = new ModeratorService(database, CLOCK);
        moderators.synchronizeConfiguredModerators(Set.of(MODERATOR_ID, OTHER_MODERATOR_ID));
        tickets = new TicketService(
                database, moderators, new CredentialService(), new ObjectMapper(), CLOCK);
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    @Test
    void approvalIsAtomicAuditedAndIdempotent() {
        long ticketId = tickets.submitRegistration(registration(2002L, "Steve"));
        String plaintext;

        try (TicketDecisionResult first = tickets.approveRegistration(
                ticketId, new ModeratorIdentity(MODERATOR_ID, "mod_name"))) {
            assertEquals(TicketDecisionResult.Outcome.APPROVED, first.outcome());
            char[] password = first.copyInitialPassword();
            assertNotNull(password);
            plaintext = new String(password);
            java.util.Arrays.fill(password, '\0');
        }

        try (TicketDecisionResult duplicate = tickets.approveRegistration(
                ticketId, new ModeratorIdentity(MODERATOR_ID, "renamed_mod"))) {
            assertEquals(TicketDecisionResult.Outcome.ALREADY_DECIDED, duplicate.outcome());
            assertEquals(null, duplicate.copyInitialPassword());
        }

        database.transaction(connection -> {
            assertEquals(1, count(connection, "minecraft_accounts"));
            assertEquals(1, count(connection, "ticket_decisions"));
            assertEquals(1, count(connection, "sync_events"));
            assertEquals(4, count(connection, "audit_log"));

            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT t.status, a.credential_verifier, s.payload_json
                    FROM tickets t
                    JOIN minecraft_accounts a ON a.owner_telegram_id = t.creator_telegram_id
                    JOIN sync_events s ON s.event_type = 'ACCOUNT_CREATED'
                    WHERE t.id = ?
                    """)) {
                statement.setLong(1, ticketId);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("RESOLVED", result.getString("status"));
                    assertTrue(result.getString("credential_verifier").startsWith("$argon2id$"));
                    assertFalse(result.getString("credential_verifier").contains(plaintext));
                    assertFalse(result.getString("payload_json").contains(plaintext));
                }
            }
            return null;
        });
    }

    @Test
    void unauthorizedModeratorCannotDecideTicket() {
        long ticketId = tickets.submitRegistration(registration(2003L, "Alex"));

        WorkflowException exception = assertThrows(WorkflowException.class,
                () -> tickets.approveRegistration(ticketId, new ModeratorIdentity(9999L, "intruder")));

        assertEquals(WorkflowException.Code.UNAUTHORIZED, exception.code());
        database.transaction(connection -> {
            assertEquals(0, count(connection, "minecraft_accounts"));
            assertEquals(0, count(connection, "ticket_decisions"));
            return null;
        });
    }

    @Test
    void moderatorCannotDecideRegistrationAssignedToAnotherModerator() {
        long ticketId = tickets.submitRegistration(registration(2010L, "AssignedPlayer"));
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE tickets SET status = 'IN_PROGRESS', assigned_moderator_telegram_id = ?
                    WHERE id = ?
                    """)) {
                statement.setLong(1, MODERATOR_ID);
                statement.setLong(2, ticketId);
                statement.executeUpdate();
            }
            return null;
        });

        WorkflowException exception = assertThrows(WorkflowException.class,
                () -> tickets.approveRegistration(ticketId,
                        new ModeratorIdentity(OTHER_MODERATOR_ID, "other_mod")));

        assertEquals(WorkflowException.Code.UNAUTHORIZED, exception.code());
    }

    @Test
    void invalidTicketStateCannotBeDecided() {
        long ticketId = tickets.submitRegistration(registration(2007L, "ClosedTicket"));
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE tickets SET status = 'CLOSED' WHERE id = ?")) {
                statement.setLong(1, ticketId);
                statement.executeUpdate();
            }
            return null;
        });

        WorkflowException exception = assertThrows(WorkflowException.class,
                () -> tickets.approveRegistration(ticketId, new ModeratorIdentity(MODERATOR_ID, "mod_name")));

        assertEquals(WorkflowException.Code.INVALID_STATE, exception.code());
        database.transaction(connection -> {
            assertEquals(0, count(connection, "minecraft_accounts"));
            assertEquals(0, count(connection, "ticket_decisions"));
            return null;
        });
    }

    @Test
    void duplicateOpenRegistrationIsRejectedCaseInsensitively() {
        tickets.submitRegistration(registration(2004L, "Builder_One"));

        WorkflowException sameNickname = assertThrows(WorkflowException.class,
                () -> tickets.submitRegistration(registration(2005L, "builder_one")));
        WorkflowException sameUser = assertThrows(WorkflowException.class,
                () -> tickets.submitRegistration(registration(2004L, "AnotherName")));

        assertEquals(WorkflowException.Code.CONFLICT, sameNickname.code());
        assertEquals(WorkflowException.Code.CONFLICT, sameUser.code());
    }

    @Test
    void invalidMinecraftNicknameIsRejectedBeforePersistence() {
        WorkflowException exception = assertThrows(WorkflowException.class,
                () -> tickets.submitRegistration(registration(2006L, "not valid!")));

        assertEquals(WorkflowException.Code.INVALID_INPUT, exception.code());
        database.transaction(connection -> {
            assertEquals(0, count(connection, "users"));
            assertEquals(0, count(connection, "tickets"));
            return null;
        });
    }

    private static RegistrationSubmission registration(long telegramId, String nickname) {
        return new RegistrationSubmission(
                telegramId, "user_" + telegramId, nickname, true, 25, "Several modpacks");
    }

    private static int count(java.sql.Connection connection, String table) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }
}
