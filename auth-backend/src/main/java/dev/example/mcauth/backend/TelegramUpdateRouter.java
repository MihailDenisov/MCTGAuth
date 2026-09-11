package dev.example.mcauth.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.example.mcauth.api.AccountCreationMethod;
import dev.example.mcauth.api.RemoteCommand;
import dev.example.mcauth.api.RemoteCommandStatus;
import dev.example.mcauth.api.RemoteCommandType;
import dev.example.mcauth.api.TicketStatus;
import dev.example.mcauth.api.TicketType;
import dev.example.mcauth.backend.telegram.i18n.BotAction;
import dev.example.mcauth.backend.telegram.i18n.BotLocale;
import dev.example.mcauth.backend.telegram.i18n.LocaleResolver;
import dev.example.mcauth.backend.telegram.i18n.LocalizationService;
import dev.example.mcauth.backend.telegram.i18n.MenuActionRouter;
import dev.example.mcauth.backend.telegram.i18n.MessageKey;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class TelegramUpdateRouter {
    private final TelegramClient telegram;
    private final long adminChatId;
    private final String howToPlayUrl;
    private final String serverId;
    private final TicketService registrationTickets;
    private final SupportTicketService tickets;
    private final AccountService accounts;
    private final ModeratorService moderators;
    private final RemoteCommandService commands;
    private final LocalizationService localization;
    private final LocaleResolver locales;
    private final MenuActionRouter actions;
    private final TelegramKeyboards keyboards;
    private final Map<Long, FlowSession> sessions = new ConcurrentHashMap<>();

    TelegramUpdateRouter(
            TelegramClient telegram,
            long adminChatId,
            String howToPlayUrl,
            String serverId,
            TicketService registrationTickets,
            SupportTicketService tickets,
            AccountService accounts,
            ModeratorService moderators,
            RemoteCommandService commands,
            LocalizationService localization,
            LocaleResolver locales,
            ObjectMapper json) {
        this.telegram = telegram;
        this.adminChatId = adminChatId;
        this.howToPlayUrl = howToPlayUrl;
        this.serverId = serverId;
        this.registrationTickets = registrationTickets;
        this.tickets = tickets;
        this.accounts = accounts;
        this.moderators = moderators;
        this.commands = commands;
        this.localization = localization;
        this.locales = locales;
        this.actions = new MenuActionRouter(localization);
        this.keyboards = new TelegramKeyboards(json, localization);
    }

    void handle(JsonNode update) throws IOException, InterruptedException {
        if (update.has("message")) handleMessage(update.path("message"));
        else if (update.has("callback_query")) handleCallback(update.path("callback_query"));
    }

    private void handleMessage(JsonNode message) throws IOException, InterruptedException {
        JsonNode from = message.path("from");
        long telegramId = from.path("id").asLong();
        long chatId = message.path("chat").path("id").asLong();
        if (telegramId <= 0 || chatId == 0 || !"private".equals(message.path("chat").path("type").asText())) return;
        String username = from.path("username").asText("");
        String text = message.path("text").asText("").strip();
        BotLocale suggestion = BotLocale.suggested(
                from.path("language_code").asText(""), locales.defaultUserLocale());
        boolean newUser = accounts.touchTelegramUser(telegramId, username, suggestion.code());
        BotLocale locale = locales.userLocale(telegramId);
        Optional<BotAction> action = actions.resolve(text);

        if ("/cancel".equals(command(text)) || action.orElse(null) == BotAction.CANCEL) {
            sessions.remove(telegramId);
            showMainMenu(chatId, telegramId, MessageKey.ACTION_CANCELLED);
            return;
        }
        try {
            if (routeCommandOrMenu(chatId, telegramId, username, text, action.orElse(null), newUser, locale)) return;
        } catch (WorkflowException | IllegalArgumentException exception) {
            telegram.sendMessage(chatId, localizedError(locale, exception), null);
            return;
        }

        FlowSession session = sessions.get(telegramId);
        if (session == null) {
            showMainMenu(chatId, telegramId, MessageKey.MAIN_CHOOSE_ACTION);
            return;
        }
        try {
            advance(chatId, telegramId, username, text, message, session, action.orElse(null), locale);
        } catch (WorkflowException | IllegalArgumentException exception) {
            telegram.sendMessage(chatId, localizedError(locale, exception), null);
        }
    }

    private boolean routeCommandOrMenu(
            long chatId, long telegramId, String username, String text,
            BotAction action, boolean newUser, BotLocale locale)
            throws IOException, InterruptedException {
        String command = command(text);
        if ("/start".equals(command)) {
            sessions.remove(telegramId);
            if (newUser) showLanguageSelector(chatId);
            else showMainMenu(chatId, telegramId, MessageKey.MAIN_WELCOME);
            return true;
        }
        if ("/register".equals(command) || action == BotAction.REGISTER) {
            beginRegistration(chatId, telegramId, username, locale);
            return true;
        }
        if (action == BotAction.REGISTRATION_STATUS) {
            sessions.remove(telegramId);
            String status = tickets.pendingRegistration(telegramId)
                    .map(ticket -> t(locale, MessageKey.REG_STATUS_PENDING,
                            Long.toString(ticket.id()), ticketStatus(locale, ticket.status())))
                    .orElseGet(() -> t(locale, MessageKey.REG_STATUS_NONE));
            telegram.sendMessage(chatId, status,
                    keyboards.userMenu(locale, false, moderators.isEnabled(telegramId)));
            return true;
        }
        if (action == BotAction.ABOUT || action == BotAction.HOW_TO_PLAY) {
            sessions.remove(telegramId);
            telegram.sendMessage(chatId, t(locale, MessageKey.HOW_TO_PLAY),
                    keyboards.inline(new String[][]{{t(locale, MessageKey.HOW_TO_PLAY_GUIDE), howToPlayUrl}}));
            return true;
        }
        if (action == BotAction.REPORT_PROBLEM) {
            beginAccountFlow(chatId, telegramId, FlowKind.PROBLEM_ACCOUNT, locale);
            return true;
        }
        if (action == BotAction.REPORT_VIOLATION) {
            beginAccountFlow(chatId, telegramId, FlowKind.VIOLATION_ACCOUNT, locale);
            return true;
        }
        if ("/tickets".equals(command) || action == BotAction.MY_TICKETS) {
            sessions.remove(telegramId);
            showMyTickets(chatId, telegramId, 0, locale);
            return true;
        }
        if ("/account".equals(command) || action == BotAction.MY_ACCOUNT) {
            sessions.remove(telegramId);
            showAccount(chatId, telegramId, locale);
            return true;
        }
        if (action == BotAction.SETTINGS) {
            sessions.remove(telegramId);
            telegram.sendMessage(chatId, t(locale, MessageKey.SETTINGS_TITLE), keyboards.languageSelector());
            return true;
        }
        if (action == BotAction.ADD_ACCOUNT) {
            requireRegistered(telegramId);
            sessions.put(telegramId, new FlowSession(FlowKind.ADD_NICKNAME));
            telegram.sendMessage(chatId, t(locale, MessageKey.ADD_NICKNAME), null);
            return true;
        }
        if (action == BotAction.CHANGE_PASSWORD) {
            sessions.remove(telegramId);
            showPasswordChoices(chatId, telegramId, locale);
            return true;
        }
        if ("/admin".equals(command) || action == BotAction.ADMINISTRATION) {
            sessions.remove(telegramId);
            requireModerator(telegramId);
            telegram.sendMessage(chatId, t(locale, MessageKey.ADMIN_TITLE), keyboards.adminMenu(locale));
            return true;
        }
        return moderators.isEnabled(telegramId)
                && routeAdminMenu(chatId, telegramId, username, text, command, action, locale);
    }

    private boolean routeAdminMenu(
            long chatId, long telegramId, String username, String text, String command,
            BotAction action, BotLocale locale) throws IOException, InterruptedException {
        ModeratorIdentity moderator = new ModeratorIdentity(telegramId, username);
        if (action == BotAction.NEW_TICKETS) {
            showAdminTickets(chatId, moderator, TicketStatus.OPEN, null, locale); return true;
        }
        if (action == BotAction.IN_PROGRESS) {
            showAdminTickets(chatId, moderator, TicketStatus.IN_PROGRESS, null, locale); return true;
        }
        if (action == BotAction.REGISTRATIONS) {
            showAdminTickets(chatId, moderator, null, TicketType.REGISTRATION, locale); return true;
        }
        if (action == BotAction.PROBLEMS) {
            showAdminTickets(chatId, moderator, null, TicketType.PROBLEM, locale); return true;
        }
        if (action == BotAction.VIOLATIONS) {
            showAdminTickets(chatId, moderator, null, TicketType.VIOLATION, locale); return true;
        }
        if (action == BotAction.SEARCH) {
            sessions.put(telegramId, new FlowSession(FlowKind.ADMIN_SEARCH));
            telegram.sendMessage(chatId, t(locale, MessageKey.ADMIN_SEARCH_PROMPT), null);
            return true;
        }
        if ("/search".equals(command) && text.contains(" ")) {
            showSearch(chatId, moderator, text.substring(text.indexOf(' ') + 1), locale); return true;
        }
        if ("/ticket".equals(command) && text.contains(" ")) {
            showAdminTicket(chatId, moderator,
                    Long.parseLong(text.substring(text.indexOf(' ') + 1).strip()), locale);
            return true;
        }
        if (action == BotAction.STATISTICS) {
            telegram.sendMessage(chatId, formatStatistics(tickets.statistics(moderator), locale),
                    keyboards.adminMenu(locale)); return true;
        }
        if (action == BotAction.MANUAL_ACCOUNT) {
            sessions.put(telegramId, new FlowSession(FlowKind.MANUAL_OWNER));
            telegram.sendMessage(chatId, t(locale, MessageKey.MANUAL_OWNER), null); return true;
        }
        if (action == BotAction.SERVER_MANAGEMENT) {
            telegram.sendMessage(chatId, t(locale, MessageKey.SERVER_TITLE, serverId),
                    keyboards.serverMenu(locale)); return true;
        }
        if (action == BotAction.FORCE_SYNC) {
            telegram.sendMessage(chatId, t(locale, MessageKey.SERVER_FORCE_CONFIRM, serverId),
                    keyboards.inline(new String[][]{
                            {t(locale, MessageKey.BUTTON_CONFIRM), "remote:force:confirm"},
                            {t(locale, MessageKey.BUTTON_CANCEL), "remote:cancel"}}));
            return true;
        }
        if (action == BotAction.AUTH_STATUS) {
            RemoteCommand queued = commands.create(moderator, RemoteCommandType.AUTH_STATUS, "{}");
            telegram.sendMessage(chatId, formatCommand(queued, locale), keyboards.serverMenu(locale)); return true;
        }
        if (action == BotAction.RESYNC_PLAYER) {
            sessions.put(telegramId, new FlowSession(FlowKind.RESYNC_ACCOUNT));
            telegram.sendMessage(chatId, t(locale, MessageKey.SERVER_RESYNC_ACCOUNT), null);
            return true;
        }
        return false;
    }

    private void advance(
            long chatId, long telegramId, String username, String text, JsonNode message,
            FlowSession session, BotAction action, BotLocale locale)
            throws IOException, InterruptedException {
        switch (session.kind) {
            case REG_NICKNAME -> {
                session.put("nickname", text); session.kind = FlowKind.REG_LICENSE;
                yesNo(chatId, locale, MessageKey.REG_LICENSE);
            }
            case REG_LICENSE -> {
                session.put("licensed", parseYesNo(action)); session.kind = FlowKind.REG_AGE;
                telegram.sendMessage(chatId, t(locale, MessageKey.REG_AGE), null);
            }
            case REG_AGE -> {
                int age = Integer.parseInt(text);
                if (age < 1 || age > 120) throw new LocalizedUiException(MessageKey.REG_AGE_INVALID);
                session.put("age", text); session.kind = FlowKind.REG_EXPERIENCE;
                telegram.sendMessage(chatId, t(locale, MessageKey.REG_EXPERIENCE), null);
            }
            case REG_EXPERIENCE -> {
                session.put("experience", text); session.kind = FlowKind.REG_CONFIRM;
                yesNo(chatId, locale, MessageKey.REG_CONFIRM,
                        session.get("nickname"), yesNoLabel(locale, Boolean.parseBoolean(session.get("licensed"))),
                        session.get("age"), session.get("experience"));
            }
            case REG_CONFIRM -> finishRegistration(
                    chatId, telegramId, username, session, parseYesNo(action), locale);
            case PROBLEM_ACCOUNT -> {
                session.put("accountId", positiveLong(text)); session.kind = FlowKind.PROBLEM_DESCRIPTION;
                telegram.sendMessage(chatId, t(locale, MessageKey.PROBLEM_DESCRIPTION), null);
            }
            case PROBLEM_DESCRIPTION -> {
                session.put("description", text); session.kind = FlowKind.PROBLEM_ATTACHMENTS;
                telegram.sendMessage(chatId, t(locale, MessageKey.PROBLEM_ATTACHMENTS),
                        keyboards.attachmentMenu(locale));
            }
            case PROBLEM_ATTACHMENTS ->
                    collectOrSubmit(chatId, telegramId, username, message, session, false, action, locale);
            case VIOLATION_ACCOUNT -> {
                session.put("accountId", positiveLong(text)); session.kind = FlowKind.VIOLATION_OFFENDER;
                telegram.sendMessage(chatId, t(locale, MessageKey.VIOLATION_OFFENDER), null);
            }
            case VIOLATION_OFFENDER -> {
                session.put("offender", text); session.kind = FlowKind.VIOLATION_DESCRIPTION;
                telegram.sendMessage(chatId, t(locale, MessageKey.VIOLATION_DESCRIPTION), null);
            }
            case VIOLATION_DESCRIPTION -> {
                session.put("description", text); session.kind = FlowKind.VIOLATION_ATTACHMENTS;
                telegram.sendMessage(chatId, t(locale, MessageKey.VIOLATION_ATTACHMENTS),
                        keyboards.attachmentMenu(locale));
            }
            case VIOLATION_ATTACHMENTS ->
                    collectOrSubmit(chatId, telegramId, username, message, session, true, action, locale);
            case ADD_NICKNAME -> {
                session.put("nickname", text); session.kind = FlowKind.ADD_LICENSE;
                yesNo(chatId, locale, MessageKey.ADD_LICENSE);
            }
            case ADD_LICENSE -> {
                session.put("licensed", parseYesNo(action)); session.kind = FlowKind.ADD_CONFIRM;
                yesNo(chatId, locale, MessageKey.ADD_CONFIRM, session.get("nickname"));
            }
            case ADD_CONFIRM ->
                    finishAddAccount(chatId, telegramId, username, session, parseYesNo(action), locale);
            case MANUAL_OWNER -> {
                session.put("ownerId", positiveLong(text)); session.kind = FlowKind.MANUAL_NICKNAME;
                telegram.sendMessage(chatId, t(locale, MessageKey.MANUAL_NICKNAME), null);
            }
            case MANUAL_NICKNAME -> {
                session.put("nickname", text); session.kind = FlowKind.MANUAL_LICENSE;
                yesNo(chatId, locale, MessageKey.MANUAL_LICENSE);
            }
            case MANUAL_LICENSE -> {
                session.put("licensed", parseYesNo(action)); session.kind = FlowKind.MANUAL_CONFIRM;
                yesNo(chatId, locale, MessageKey.MANUAL_CONFIRM);
            }
            case MANUAL_CONFIRM ->
                    finishManual(chatId, telegramId, username, session, parseYesNo(action), locale);
            case MODERATOR_REPLY -> {
                long ticketId = Long.parseLong(session.get("ticketId"));
                ModeratorIdentity moderator = new ModeratorIdentity(telegramId, username);
                tickets.moderatorReply(ticketId, moderator, text);
                sessions.remove(telegramId);
                TicketDetail detail = tickets.ticketForModerator(moderator, ticketId);
                BotLocale userLocale = locales.userLocale(detail.ticket().creatorTelegramId());
                telegram.sendMessage(detail.ticket().creatorTelegramId(),
                        t(userLocale, MessageKey.TICKET_REPLY_FOR_USER, Long.toString(ticketId), text),
                        keyboards.inline(new String[][]{{t(userLocale, MessageKey.BUTTON_REPLY),
                                "ticket:" + ticketId + ":user_reply"}}));
                telegram.sendMessage(chatId, t(locale, MessageKey.TICKET_REPLY_SENT), keyboards.adminMenu(locale));
            }
            case USER_REPLY -> {
                long ticketId = Long.parseLong(session.get("ticketId"));
                tickets.userReply(telegramId, ticketId, text);
                sessions.remove(telegramId);
                telegram.sendMessage(adminChatId,
                        t(locales.adminLocale(), MessageKey.TICKET_USER_REPLIED_ADMIN,
                                Long.toString(ticketId), text), null);
                showMainMenu(chatId, telegramId, MessageKey.TICKET_REPLY_SENT);
            }
            case ADMIN_SEARCH -> {
                sessions.remove(telegramId);
                showSearch(chatId, new ModeratorIdentity(telegramId, username), text, locale);
            }
            case RESYNC_ACCOUNT -> {
                long accountId = Long.parseLong(positiveLong(text));
                sessions.remove(telegramId);
                RemoteCommand queued = commands.create(new ModeratorIdentity(telegramId, username),
                        RemoteCommandType.RESYNC_PLAYER, "{\"accountId\":" + accountId + "}");
                telegram.sendMessage(chatId, formatCommand(queued, locale), keyboards.serverMenu(locale));
            }
        }
    }

    private void handleCallback(JsonNode callback) throws IOException, InterruptedException {
        String callbackId = callback.path("id").asText();
        JsonNode from = callback.path("from");
        long telegramId = from.path("id").asLong();
        String username = from.path("username").asText("");
        long chatId = callback.path("message").path("chat").path("id").asLong();
        String chatType = callback.path("message").path("chat").path("type").asText("");
        long messageId = callback.path("message").path("message_id").asLong();
        String data = callback.path("data").asText();
        BotLocale suggestion = BotLocale.suggested(
                from.path("language_code").asText(""), locales.defaultUserLocale());
        accounts.touchTelegramUser(telegramId, username, suggestion.code());
        BotLocale locale = chatId == adminChatId ? locales.adminLocale() : locales.userLocale(telegramId);
        try {
            if (data.startsWith("locale:set:")) {
                if (!"private".equals(chatType)) throw new WorkflowException(
                        WorkflowException.Code.UNAUTHORIZED, "Private chat required");
                BotLocale selected = BotLocale.configured(data.substring("locale:set:".length()), "locale");
                locales.setUserLocale(telegramId, selected);
                sessions.remove(telegramId);
                showMainMenu(chatId, telegramId, MessageKey.LANGUAGE_CHANGED);
                telegram.answerCallback(callbackId, t(selected, MessageKey.LANGUAGE_CHANGED));
                return;
            }
            if (data.startsWith("tickets:page:")) {
                showMyTickets(chatId, telegramId,
                        Integer.parseInt(data.substring("tickets:page:".length())), locale);
            } else if (data.startsWith("ticket:")) {
                handleTicketCallback(callbackId, chatId, messageId, telegramId, username, data, locale);
                return;
            } else if (data.startsWith("account:")) {
                handleAccountCallback(callbackId, chatId, telegramId, data, locale);
                return;
            } else if ("remote:force:confirm".equals(data)) {
                requireModerator(telegramId);
                RemoteCommand queued = commands.create(new ModeratorIdentity(telegramId, username),
                        RemoteCommandType.FORCE_AUTH_SYNC, "{}");
                telegram.sendMessage(chatId, formatCommand(queued, locale), keyboards.serverMenu(locale));
            } else if ("remote:cancel".equals(data)) {
                telegram.sendMessage(chatId, t(locale, MessageKey.SERVER_TITLE, serverId),
                        keyboards.serverMenu(locale));
            } else {
                throw new LocalizedUiException(MessageKey.ERROR_UNKNOWN_ACTION);
            }
            telegram.answerCallback(callbackId, t(locale, MessageKey.CALLBACK_DONE));
        } catch (WorkflowException | IllegalArgumentException exception) {
            telegram.answerCallback(callbackId, localizedError(locale, exception));
        }
    }

    private void handleTicketCallback(
            String callbackId, long chatId, long messageId, long telegramId, String username,
            String data, BotLocale locale) throws IOException, InterruptedException {
        String[] parts = data.split(":");
        if (parts.length != 3) throw new LocalizedUiException(MessageKey.ERROR_UNKNOWN_ACTION);
        long ticketId = Long.parseLong(parts[1]);
        String action = parts[2];
        if ("view".equals(action)) {
            showUserTicket(chatId, telegramId, ticketId, locale);
        } else if ("user_reply".equals(action)) {
            tickets.ticketForUser(telegramId, ticketId);
            FlowSession session = new FlowSession(FlowKind.USER_REPLY);
            session.put("ticketId", ticketId);
            sessions.put(telegramId, session);
            telegram.sendMessage(chatId,
                    t(locale, MessageKey.TICKET_REPLY_PROMPT, Long.toString(ticketId)), null);
        } else {
            if (chatId != adminChatId) {
                throw new WorkflowException(WorkflowException.Code.UNAUTHORIZED, "Admin chat required");
            }
            ModeratorIdentity moderator = new ModeratorIdentity(telegramId, username);
            switch (action) {
                case "take" -> {
                    tickets.claim(ticketId, moderator);
                    telegram.editMessage(chatId, messageId,
                            t(locale, MessageKey.TICKET_ADMIN_TAKEN,
                                    Long.toString(ticketId), Long.toString(telegramId)));
                }
                case "reply" -> {
                    tickets.ticketForModerator(moderator, ticketId);
                    FlowSession session = new FlowSession(FlowKind.MODERATOR_REPLY);
                    session.put("ticketId", ticketId);
                    sessions.put(telegramId, session);
                    BotLocale moderatorLocale = locales.userLocale(telegramId);
                    telegram.sendMessage(telegramId,
                            t(moderatorLocale, MessageKey.TICKET_REPLY_PROMPT, Long.toString(ticketId)), null);
                }
                case "resolve" -> {
                    tickets.transition(ticketId, moderator, TicketStatus.RESOLVED);
                    TicketDetail detail = tickets.ticketForModerator(moderator, ticketId);
                    BotLocale userLocale = locales.userLocale(detail.ticket().creatorTelegramId());
                    telegram.sendMessage(detail.ticket().creatorTelegramId(),
                            t(userLocale, MessageKey.TICKET_RESOLVED_USER, Long.toString(ticketId)), null);
                    telegram.editMessage(chatId, messageId,
                            t(locale, MessageKey.TICKET_ADMIN_TERMINAL,
                                    Long.toString(ticketId), ticketStatus(locale, TicketStatus.RESOLVED)));
                }
                case "reject" -> rejectTicket(chatId, messageId, ticketId, moderator, locale);
                case "approve" -> approveTicket(chatId, messageId, ticketId, moderator, locale);
                default -> throw new LocalizedUiException(MessageKey.ERROR_UNKNOWN_ACTION);
            }
        }
        telegram.answerCallback(callbackId, t(locale, MessageKey.CALLBACK_DONE));
    }

    private void handleAccountCallback(
            String callbackId, long chatId, long telegramId, String data, BotLocale locale)
            throws IOException, InterruptedException {
        String[] parts = data.split(":");
        if (parts.length != 3) throw new LocalizedUiException(MessageKey.ERROR_UNKNOWN_ACTION);
        long accountId = Long.parseLong(parts[1]);
        AccountView account = accounts.accountsFor(telegramId).stream()
                .filter(candidate -> candidate.id() == accountId)
                .findFirst()
                .orElseThrow(() -> new WorkflowException(
                        WorkflowException.Code.UNAUTHORIZED, "Account ownership required"));
        if ("password".equals(parts[2])) {
            telegram.sendMessage(chatId, t(locale, MessageKey.PASSWORD_CONFIRM, account.nickname()),
                    keyboards.inline(new String[][]{
                            {t(locale, MessageKey.BUTTON_CONFIRM), "account:" + accountId + ":password_confirm"},
                            {t(locale, MessageKey.BUTTON_CANCEL), "account:" + accountId + ":cancel"}}));
        } else if ("password_confirm".equals(parts[2])) {
            try (CredentialIssueResult result = accounts.changePassword(telegramId, accountId)) {
                sendCredential(chatId, result, locale);
            }
        } else if ("cancel".equals(parts[2])) {
            showAccount(chatId, telegramId, locale);
        } else {
            throw new LocalizedUiException(MessageKey.ERROR_UNKNOWN_ACTION);
        }
        telegram.answerCallback(callbackId, t(locale, MessageKey.CALLBACK_DONE));
    }

    private void approveTicket(
            long chatId, long messageId, long ticketId, ModeratorIdentity moderator, BotLocale adminLocale)
            throws IOException, InterruptedException {
        TicketDetail detail = tickets.ticketForModerator(moderator, ticketId);
        TicketDecisionResult result = detail.ticket().type() == TicketType.REGISTRATION
                ? registrationTickets.approveRegistration(ticketId, moderator)
                : detail.ticket().type() == TicketType.ADD_MINECRAFT_ACCOUNT
                    ? tickets.approveAddAccount(ticketId, moderator)
                    : null;
        if (result == null) throw new LocalizedUiException(MessageKey.ERROR_INVALID_STATE);
        try (result) {
            if (result.outcome() == TicketDecisionResult.Outcome.ALREADY_DECIDED) return;
            char[] password = result.copyInitialPassword();
            try {
                BotLocale userLocale = locales.userLocale(result.creatorTelegramId());
                telegram.sendMessage(result.creatorTelegramId(),
                        t(userLocale, MessageKey.TICKET_APPROVED_USER,
                                result.nickname(), new String(password)), null);
            } finally {
                Arrays.fill(password, '\0');
            }
            telegram.editMessage(chatId, messageId,
                    t(adminLocale, MessageKey.TICKET_ADMIN_TERMINAL,
                            Long.toString(ticketId), ticketStatus(adminLocale, TicketStatus.RESOLVED)));
        }
    }

    private void rejectTicket(
            long chatId, long messageId, long ticketId, ModeratorIdentity moderator, BotLocale adminLocale)
            throws IOException, InterruptedException {
        TicketDetail detail = tickets.ticketForModerator(moderator, ticketId);
        if (detail.ticket().type() == TicketType.REGISTRATION) {
            try (TicketDecisionResult result = registrationTickets.rejectRegistration(ticketId, moderator, "")) {
                if (result.outcome() == TicketDecisionResult.Outcome.ALREADY_DECIDED) return;
            }
        } else if (detail.ticket().type() == TicketType.ADD_MINECRAFT_ACCOUNT) {
            try (TicketDecisionResult result = tickets.rejectAddAccount(ticketId, moderator)) {
                if (result.outcome() == TicketDecisionResult.Outcome.ALREADY_DECIDED) return;
            }
        } else {
            tickets.transition(ticketId, moderator, TicketStatus.REJECTED);
        }
        BotLocale userLocale = locales.userLocale(detail.ticket().creatorTelegramId());
        telegram.sendMessage(detail.ticket().creatorTelegramId(),
                t(userLocale, MessageKey.TICKET_REJECTED_USER, Long.toString(ticketId)), null);
        telegram.editMessage(chatId, messageId,
                t(adminLocale, MessageKey.TICKET_ADMIN_TERMINAL,
                        Long.toString(ticketId), ticketStatus(adminLocale, TicketStatus.REJECTED)));
    }

    private void beginRegistration(
            long chatId, long telegramId, String username, BotLocale locale)
            throws IOException, InterruptedException {
        if (accounts.hasEnabledAccount(telegramId)) {
            telegram.sendMessage(chatId, t(locale, MessageKey.REG_ALREADY_REGISTERED),
                    keyboards.userMenu(locale, true, moderators.isEnabled(telegramId)));
            return;
        }
        var pending = tickets.pendingRegistration(telegramId);
        if (pending.isPresent()) {
            telegram.sendMessage(chatId,
                    t(locale, MessageKey.REG_STATUS_PENDING, Long.toString(pending.get().id()),
                            ticketStatus(locale, pending.get().status())),
                    keyboards.userMenu(locale, false, moderators.isEnabled(telegramId)));
            return;
        }
        sessions.put(telegramId, new FlowSession(FlowKind.REG_NICKNAME));
        telegram.sendMessage(chatId, t(locale, MessageKey.REG_NICKNAME), null);
    }

    private void finishRegistration(
            long chatId, long telegramId, String username, FlowSession session,
            boolean confirmed, BotLocale locale) throws IOException, InterruptedException {
        if (!confirmed) {
            sessions.remove(telegramId);
            showMainMenu(chatId, telegramId, MessageKey.REG_CANCELLED);
            return;
        }
        long ticketId = registrationTickets.submitRegistration(new RegistrationSubmission(
                telegramId, username, session.get("nickname"),
                Boolean.parseBoolean(session.get("licensed")),
                Integer.parseInt(session.get("age")), session.get("experience")));
        sessions.remove(telegramId);
        telegram.sendMessage(chatId,
                t(locale, MessageKey.REG_SUBMITTED, Long.toString(ticketId)),
                keyboards.userMenu(locale, false, moderators.isEnabled(telegramId)));

        TicketDetail detail = tickets.ticketForUser(telegramId, ticketId);
        BotLocale adminLocale = locales.adminLocale();
        long adminMessage = telegram.sendMessage(adminChatId,
                formatTicket(detail, adminLocale), adminTicketKeyboard(ticketId, true, adminLocale));
        registrationTickets.attachAdminProjection(ticketId, adminChatId, adminMessage);
    }

    private void beginAccountFlow(
            long chatId, long telegramId, FlowKind kind, BotLocale locale)
            throws IOException, InterruptedException {
        List<AccountView> owned = requireRegistered(telegramId);
        FlowSession session = new FlowSession(kind);
        sessions.put(telegramId, session);
        if (owned.size() == 1) {
            session.put("accountId", owned.getFirst().id());
            session.kind = kind == FlowKind.PROBLEM_ACCOUNT
                    ? FlowKind.PROBLEM_DESCRIPTION : FlowKind.VIOLATION_OFFENDER;
            telegram.sendMessage(chatId,
                    t(locale, kind == FlowKind.PROBLEM_ACCOUNT
                            ? MessageKey.PROBLEM_DESCRIPTION : MessageKey.VIOLATION_OFFENDER), null);
        } else {
            MessageKey key = kind == FlowKind.PROBLEM_ACCOUNT
                    ? MessageKey.PROBLEM_SELECT_ACCOUNT : MessageKey.VIOLATION_SELECT_ACCOUNT;
            telegram.sendMessage(chatId, t(locale, key, formatAccounts(owned, locale)), null);
        }
    }

    private void collectOrSubmit(
            long chatId, long telegramId, String username, JsonNode message,
            FlowSession session, boolean violation, BotAction action, BotLocale locale)
            throws IOException, InterruptedException {
        if (action == BotAction.SUBMIT) {
            long accountId = Long.parseLong(session.get("accountId"));
            long ticketId = violation
                    ? tickets.createViolation(telegramId, username, accountId, session.get("offender"),
                            session.get("description"), session.attachments)
                    : tickets.createProblem(telegramId, username, accountId,
                            session.get("description"), session.attachments);
            sessions.remove(telegramId);
            showMainMenu(chatId, telegramId, MessageKey.TICKET_SUBMITTED, Long.toString(ticketId));
            postSupportTicket(ticketId, telegramId);
            return;
        }
        if (action == BotAction.ADD_ATTACHMENT) {
            telegram.sendMessage(chatId, t(locale, MessageKey.ATTACHMENT_SEND),
                    keyboards.attachmentMenu(locale));
            return;
        }
        AttachmentReference attachment = extractAttachment(message);
        if (attachment == null) throw new LocalizedUiException(MessageKey.ATTACHMENT_INVALID);
        if (session.attachments.size() >= 10) throw new LocalizedUiException(MessageKey.ATTACHMENT_LIMIT);
        session.attachments.add(attachment);
        telegram.sendMessage(chatId,
                t(locale, MessageKey.ATTACHMENT_ADDED, Integer.toString(session.attachments.size())),
                keyboards.attachmentMenu(locale));
    }

    private void finishAddAccount(
            long chatId, long telegramId, String username, FlowSession session,
            boolean confirmed, BotLocale locale) throws IOException, InterruptedException {
        if (!confirmed) {
            sessions.remove(telegramId);
            showMainMenu(chatId, telegramId, MessageKey.TICKET_CANCELLED);
            return;
        }
        long ticketId = tickets.createAddAccountRequest(
                telegramId, username, session.get("nickname"),
                Boolean.parseBoolean(session.get("licensed")));
        sessions.remove(telegramId);
        showMainMenu(chatId, telegramId, MessageKey.TICKET_SUBMITTED, Long.toString(ticketId));
        postSupportTicket(ticketId, telegramId);
    }

    private void finishManual(
            long chatId, long telegramId, String username, FlowSession session,
            boolean confirmed, BotLocale locale) throws IOException, InterruptedException {
        if (!confirmed) {
            sessions.remove(telegramId);
            telegram.sendMessage(chatId, t(locale, MessageKey.MANUAL_CANCELLED),
                    keyboards.adminMenu(locale));
            return;
        }
        long ownerId = Long.parseLong(session.get("ownerId"));
        try (CredentialIssueResult result = accounts.createManual(
                new ModeratorIdentity(telegramId, username), ownerId,
                session.get("nickname"), Boolean.parseBoolean(session.get("licensed")))) {
            sessions.remove(telegramId);
            char[] password = result.copyPassword();
            try {
                BotLocale ownerLocale = locales.userLocale(ownerId);
                telegram.sendMessage(ownerId,
                        t(ownerLocale, MessageKey.CREDENTIAL_CREATED,
                                result.nickname(), new String(password)), null);
            } finally {
                Arrays.fill(password, '\0');
            }
            telegram.sendMessage(chatId, t(locale, MessageKey.MANUAL_CREATED),
                    keyboards.adminMenu(locale));
        }
    }

    private void showLanguageSelector(long chatId) throws IOException, InterruptedException {
        telegram.sendMessage(chatId,
                t(BotLocale.ENGLISH, MessageKey.LANGUAGE_CHOOSE), keyboards.languageSelector());
    }

    private void showMainMenu(long chatId, long telegramId, MessageKey message, Object... arguments)
            throws IOException, InterruptedException {
        BotLocale locale = locales.userLocale(telegramId);
        telegram.sendMessage(chatId, t(locale, message, arguments),
                keyboards.userMenu(locale, accounts.hasEnabledAccount(telegramId),
                        moderators.isEnabled(telegramId)));
    }

    private void showAccount(long chatId, long telegramId, BotLocale locale)
            throws IOException, InterruptedException {
        List<AccountView> owned = requireRegistered(telegramId);
        telegram.sendMessage(chatId,
                t(locale, MessageKey.ACCOUNT_LIST, Long.toString(telegramId), formatAccounts(owned, locale)),
                keyboards.accountMenu(locale));
    }

    private void showPasswordChoices(long chatId, long telegramId, BotLocale locale)
            throws IOException, InterruptedException {
        List<AccountView> owned = requireRegistered(telegramId);
        String[][] buttons = owned.stream()
                .map(account -> new String[]{account.nickname(), "account:" + account.id() + ":password"})
                .toArray(String[][]::new);
        telegram.sendMessage(chatId, t(locale, MessageKey.ACCOUNT_SELECT_PASSWORD),
                keyboards.inline(buttons));
    }

    private void showMyTickets(long chatId, long telegramId, int requestedPage, BotLocale locale)
            throws IOException, InterruptedException {
        long total = tickets.ticketCountForUser(telegramId);
        int pages = Math.max(1, (int) Math.ceil(total / (double) SupportTicketService.PAGE_SIZE));
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        List<TicketListItem> items = tickets.ticketsForUser(telegramId, page);
        StringBuilder text = new StringBuilder(t(locale, MessageKey.TICKET_MY_TITLE)).append("\n\n");
        List<String[]> buttons = new ArrayList<>();
        if (items.isEmpty()) {
            text.append(t(locale, MessageKey.TICKET_EMPTY)).append("\n\n");
        }
        for (TicketListItem item : items) {
            String type = ticketType(locale, item.type());
            text.append('#').append(item.id()).append(' ').append(type)
                    .append("\n").append(ticketStatus(locale, item.status()))
                    .append(" · ").append(localization.formatInstant(locale, item.createdAt()))
                    .append("\n\n");
            buttons.add(new String[]{"#" + item.id() + " " + type, "ticket:" + item.id() + ":view"});
        }
        if (page > 0) buttons.add(new String[]{
                t(locale, MessageKey.BUTTON_PREVIOUS), "tickets:page:" + (page - 1)});
        if (page + 1 < pages) buttons.add(new String[]{
                t(locale, MessageKey.BUTTON_NEXT), "tickets:page:" + (page + 1)});
        text.append(t(locale, MessageKey.TICKET_PAGE,
                Integer.toString(page + 1), Integer.toString(pages)));
        telegram.sendMessage(chatId, text.toString(),
                keyboards.inline(buttons.toArray(String[][]::new)));
    }

    private void showUserTicket(long chatId, long telegramId, long ticketId, BotLocale locale)
            throws IOException, InterruptedException {
        TicketDetail detail = tickets.ticketForUser(telegramId, ticketId);
        List<String[]> buttons = new ArrayList<>();
        if (detail.ticket().status() == TicketStatus.WAITING_USER) {
            buttons.add(new String[]{t(locale, MessageKey.BUTTON_REPLY),
                    "ticket:" + ticketId + ":user_reply"});
        }
        telegram.sendMessage(chatId, formatTicket(detail, locale),
                keyboards.inline(buttons.toArray(String[][]::new)));
    }

    private void showAdminTickets(
            long chatId, ModeratorIdentity moderator, TicketStatus status,
            TicketType type, BotLocale locale) throws IOException, InterruptedException {
        List<TicketListItem> items = tickets.adminTickets(moderator, status, type, 0);
        if (items.isEmpty()) {
            telegram.sendMessage(chatId, t(locale, MessageKey.ADMIN_NO_TICKETS),
                    keyboards.adminMenu(locale));
            return;
        }
        for (TicketListItem item : items) showAdminTicket(chatId, moderator, item.id(), locale);
    }

    private void showAdminTicket(
            long chatId, ModeratorIdentity moderator, long ticketId, BotLocale locale)
            throws IOException, InterruptedException {
        TicketDetail detail = tickets.ticketForModerator(moderator, ticketId);
        boolean approvable = detail.ticket().type() == TicketType.REGISTRATION
                || detail.ticket().type() == TicketType.ADD_MINECRAFT_ACCOUNT;
        telegram.sendMessage(chatId, formatTicket(detail, locale),
                adminTicketKeyboard(ticketId, approvable, locale));
    }

    private void showSearch(
            long chatId, ModeratorIdentity moderator, String query, BotLocale locale)
            throws IOException, InterruptedException {
        List<TicketListItem> results = tickets.search(moderator, query);
        if (results.isEmpty()) {
            telegram.sendMessage(chatId, t(locale, MessageKey.ADMIN_NOTHING_FOUND),
                    keyboards.adminMenu(locale));
            return;
        }
        for (TicketListItem item : results) showAdminTicket(chatId, moderator, item.id(), locale);
    }

    private void postSupportTicket(long ticketId, long ownerId)
            throws IOException, InterruptedException {
        TicketDetail detail = tickets.ticketForUser(ownerId, ticketId);
        boolean approvable = detail.ticket().type() == TicketType.ADD_MINECRAFT_ACCOUNT;
        BotLocale adminLocale = locales.adminLocale();
        String card = formatTicket(detail, adminLocale);
        if (approvable) {
            card += "\n\n" + t(adminLocale, MessageKey.TICKET_EXISTING_ACCOUNTS,
                    formatAccounts(accounts.accountsFor(ownerId), adminLocale));
        }
        long messageId = telegram.sendMessage(adminChatId, card,
                adminTicketKeyboard(ticketId, approvable, adminLocale));
        tickets.attachAdminProjection(ticketId, adminChatId, messageId);
        for (AttachmentReference attachment : detail.attachments()) {
            telegram.sendAttachment(adminChatId, attachment);
        }
    }

    private ObjectNode adminTicketKeyboard(long ticketId, boolean approvable, BotLocale locale) {
        List<String[]> buttons = new ArrayList<>();
        buttons.add(new String[]{t(locale, MessageKey.BUTTON_TAKE), "ticket:" + ticketId + ":take"});
        buttons.add(new String[]{t(locale, MessageKey.BUTTON_REPLY), "ticket:" + ticketId + ":reply"});
        if (approvable) {
            buttons.add(new String[]{t(locale, MessageKey.BUTTON_APPROVE),
                    "ticket:" + ticketId + ":approve"});
        } else {
            buttons.add(new String[]{t(locale, MessageKey.BUTTON_RESOLVE),
                    "ticket:" + ticketId + ":resolve"});
        }
        buttons.add(new String[]{t(locale, MessageKey.BUTTON_REJECT),
                "ticket:" + ticketId + ":reject"});
        return keyboards.inline(buttons.toArray(String[][]::new));
    }

    private List<AccountView> requireRegistered(long telegramId) {
        List<AccountView> owned = accounts.accountsFor(telegramId);
        if (owned.isEmpty()) {
            throw new WorkflowException(WorkflowException.Code.UNAUTHORIZED, "Registration required");
        }
        return owned;
    }

    private void requireModerator(long telegramId) {
        if (!moderators.isEnabled(telegramId)) {
            throw new WorkflowException(WorkflowException.Code.UNAUTHORIZED, "Moderator access required");
        }
    }

    private void sendCredential(long chatId, CredentialIssueResult result, BotLocale locale)
            throws IOException, InterruptedException {
        char[] password = result.copyPassword();
        try {
            telegram.sendMessage(chatId, t(locale, MessageKey.PASSWORD_CHANGED,
                    result.nickname(), new String(password),
                    Integer.toString(result.credentialVersion())), null);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private void yesNo(long chatId, BotLocale locale, MessageKey prompt, Object... arguments)
            throws IOException, InterruptedException {
        telegram.sendMessage(chatId, t(locale, prompt, arguments), keyboards.yesNo(locale));
    }

    private String formatAccounts(List<AccountView> accountViews, BotLocale locale) {
        StringBuilder text = new StringBuilder();
        for (AccountView account : accountViews) {
            text.append(t(locale, MessageKey.ACCOUNT_ROW,
                    Long.toString(account.id()), account.nickname(),
                    t(locale, account.enabled() ? MessageKey.ACCOUNT_ENABLED : MessageKey.ACCOUNT_DISABLED),
                    t(locale, account.licensed() ? MessageKey.ACCOUNT_LICENSED : MessageKey.ACCOUNT_OFFLINE),
                    accountCreation(locale, account.creationMethod()))).append('\n');
        }
        return text.toString().stripTrailing();
    }

    private String formatTicket(TicketDetail detail, BotLocale locale) {
        TicketListItem ticket = detail.ticket();
        StringBuilder text = new StringBuilder(t(locale, MessageKey.TICKET_CARD,
                Long.toString(ticket.id()), ticketType(locale, ticket.type()),
                ticketStatus(locale, ticket.status()), Long.toString(ticket.creatorTelegramId()),
                localization.formatInstant(locale, ticket.createdAt())));
        if (!ticket.creatorUsername().isBlank()) {
            text.append('\n').append(t(locale, MessageKey.TICKET_USERNAME, ticket.creatorUsername()));
        }
        detail.fields().forEach((key, value) -> text.append('\n')
                .append(fieldLabel(locale, key)).append(": ").append(fieldValue(locale, key, value)));
        if (!detail.messages().isEmpty()) {
            text.append("\n\n").append(t(locale, MessageKey.TICKET_CONVERSATION));
            for (TicketMessageView message : detail.messages()) {
                text.append('\n').append(t(locale, MessageKey.TICKET_MESSAGE,
                        sender(locale, message.senderType()),
                        localization.formatInstant(locale, message.createdAt()), message.text()));
            }
        }
        if (!detail.attachments().isEmpty()) {
            text.append('\n').append(t(locale, MessageKey.TICKET_ATTACHMENTS_COUNT,
                    Integer.toString(detail.attachments().size())));
        }
        return text.toString();
    }

    private String formatStatistics(TicketStatistics statistics, BotLocale locale) {
        return t(locale, MessageKey.STATISTICS,
                Long.toString(statistics.total()), Long.toString(statistics.open()),
                Long.toString(statistics.inProgress()), Long.toString(statistics.resolved()),
                Long.toString(statistics.rejected()), Long.toString(statistics.registrations()),
                Long.toString(statistics.problems()), Long.toString(statistics.violations()),
                Long.toString(statistics.addAccountRequests()), Long.toString(statistics.last24Hours()),
                Long.toString(statistics.last7Days()));
    }

    private String formatCommand(RemoteCommand command, BotLocale locale) {
        return t(locale, MessageKey.COMMAND_QUEUED, command.id().toString(), command.serverId(),
                commandType(locale, command.type()), commandStatus(locale, command.status()),
                localization.formatInstant(locale, command.expiresAt()));
    }

    private String localizedError(BotLocale locale, RuntimeException exception) {
        if (exception instanceof LocalizedUiException localized) return t(locale, localized.key);
        if (exception instanceof WorkflowException workflow) {
            return t(locale, switch (workflow.code()) {
                case INVALID_INPUT -> MessageKey.ERROR_INVALID_INPUT;
                case UNAUTHORIZED -> MessageKey.ERROR_UNAUTHORIZED;
                case NOT_FOUND -> MessageKey.ERROR_NOT_FOUND;
                case INVALID_STATE -> MessageKey.ERROR_INVALID_STATE;
                case CONFLICT -> MessageKey.ERROR_CONFLICT;
                case RATE_LIMITED -> MessageKey.ERROR_RATE_LIMITED;
            });
        }
        return t(locale, MessageKey.ERROR_INVALID_INPUT);
    }

    private String ticketType(BotLocale locale, TicketType type) {
        return t(locale, switch (type) {
            case REGISTRATION -> MessageKey.TYPE_REGISTRATION;
            case ADD_MINECRAFT_ACCOUNT -> MessageKey.TYPE_ADD_ACCOUNT;
            case PROBLEM -> MessageKey.TYPE_PROBLEM;
            case VIOLATION -> MessageKey.TYPE_VIOLATION;
        });
    }

    private String ticketStatus(BotLocale locale, TicketStatus status) {
        return t(locale, switch (status) {
            case OPEN -> MessageKey.STATUS_OPEN;
            case IN_PROGRESS -> MessageKey.STATUS_IN_PROGRESS;
            case WAITING_USER -> MessageKey.STATUS_WAITING_USER;
            case RESOLVED -> MessageKey.STATUS_RESOLVED;
            case REJECTED -> MessageKey.STATUS_REJECTED;
            case CLOSED -> MessageKey.STATUS_CLOSED;
        });
    }

    private String commandType(BotLocale locale, RemoteCommandType type) {
        return t(locale, switch (type) {
            case FORCE_AUTH_SYNC -> MessageKey.COMMAND_FORCE_SYNC;
            case AUTH_STATUS -> MessageKey.COMMAND_AUTH_STATUS;
            case RESYNC_PLAYER -> MessageKey.COMMAND_RESYNC_PLAYER;
            default -> MessageKey.ERROR_UNKNOWN_ACTION;
        });
    }

    private String commandStatus(BotLocale locale, RemoteCommandStatus status) {
        return t(locale, switch (status) {
            case PENDING -> MessageKey.COMMAND_PENDING;
            case RUNNING -> MessageKey.COMMAND_RUNNING;
            case SUCCESS -> MessageKey.COMMAND_SUCCESS;
            case FAILED -> MessageKey.COMMAND_FAILED;
            case EXPIRED -> MessageKey.COMMAND_EXPIRED;
            case CANCELLED -> MessageKey.COMMAND_CANCELLED;
        });
    }

    private String accountCreation(BotLocale locale, AccountCreationMethod method) {
        return t(locale, switch (method) {
            case REGISTRATION_TICKET -> MessageKey.CREATION_REGISTRATION;
            case ADD_ACCOUNT_TICKET -> MessageKey.CREATION_ADD_ACCOUNT;
            case ADMIN_MANUAL -> MessageKey.CREATION_MANUAL;
        });
    }

    private String fieldLabel(BotLocale locale, String field) {
        return t(locale, switch (field) {
            case "minecraft_nickname" -> MessageKey.LABEL_MINECRAFT;
            case "licensed" -> MessageKey.LABEL_LICENSED;
            case "age" -> MessageKey.LABEL_AGE;
            case "experience" -> MessageKey.LABEL_EXPERIENCE;
            case "description" -> MessageKey.LABEL_DESCRIPTION;
            case "offender_nickname" -> MessageKey.LABEL_OFFENDER;
            default -> MessageKey.ERROR_UNKNOWN_ACTION;
        });
    }

    private String fieldValue(BotLocale locale, String field, String value) {
        if ("licensed".equals(field)) {
            return yesNoLabel(locale, "1".equals(value) || Boolean.parseBoolean(value));
        }
        return value;
    }

    private String sender(BotLocale locale, String senderType) {
        return t(locale, switch (senderType) {
            case "USER" -> MessageKey.LABEL_SENDER_USER;
            case "MODERATOR" -> MessageKey.LABEL_SENDER_MODERATOR;
            default -> MessageKey.LABEL_SENDER_SYSTEM;
        });
    }

    private String yesNoLabel(BotLocale locale, boolean value) {
        return t(locale, value ? MessageKey.BUTTON_YES : MessageKey.BUTTON_NO);
    }

    private String t(BotLocale locale, MessageKey key, Object... arguments) {
        return localization.text(locale, key, arguments);
    }

    private static boolean parseYesNo(BotAction action) {
        if (action == BotAction.YES) return true;
        if (action == BotAction.NO) return false;
        throw new LocalizedUiException(MessageKey.ERROR_INVALID_INPUT);
    }

    private static String positiveLong(String text) {
        long value = Long.parseLong(text.strip());
        if (value <= 0) throw new LocalizedUiException(MessageKey.ERROR_INVALID_INPUT);
        return Long.toString(value);
    }

    private static AttachmentReference extractAttachment(JsonNode message) {
        String caption = message.path("caption").asText("");
        if (message.path("photo").isArray() && !message.path("photo").isEmpty()) {
            JsonNode photo = message.path("photo").get(message.path("photo").size() - 1);
            return new AttachmentReference(photo.path("file_id").asText(),
                    photo.path("file_unique_id").asText(), "PHOTO", caption);
        }
        for (String type : List.of("video", "document", "animation")) {
            JsonNode media = message.path(type);
            if (media.isObject()) {
                return new AttachmentReference(media.path("file_id").asText(),
                        media.path("file_unique_id").asText(),
                        type.toUpperCase(Locale.ROOT), caption);
            }
        }
        return null;
    }

    private static String command(String text) {
        if (text == null || !text.startsWith("/")) return "";
        String first = text.split("\\s+", 2)[0];
        int mention = first.indexOf('@');
        return (mention < 0 ? first : first.substring(0, mention)).toLowerCase(Locale.ROOT);
    }

    private enum FlowKind {
        REG_NICKNAME, REG_LICENSE, REG_AGE, REG_EXPERIENCE, REG_CONFIRM,
        PROBLEM_ACCOUNT, PROBLEM_DESCRIPTION, PROBLEM_ATTACHMENTS,
        VIOLATION_ACCOUNT, VIOLATION_OFFENDER, VIOLATION_DESCRIPTION, VIOLATION_ATTACHMENTS,
        ADD_NICKNAME, ADD_LICENSE, ADD_CONFIRM,
        MANUAL_OWNER, MANUAL_NICKNAME, MANUAL_LICENSE, MANUAL_CONFIRM,
        MODERATOR_REPLY, USER_REPLY, ADMIN_SEARCH, RESYNC_ACCOUNT
    }

    private static final class FlowSession {
        private FlowKind kind;
        private final Map<String, String> values = new ConcurrentHashMap<>();
        private final List<AttachmentReference> attachments = new ArrayList<>();

        private FlowSession(FlowKind kind) { this.kind = kind; }
        private void put(String key, Object value) { values.put(key, String.valueOf(value)); }
        private String get(String key) { return values.get(key); }
    }

    private static final class LocalizedUiException extends IllegalArgumentException {
        private final MessageKey key;

        private LocalizedUiException(MessageKey key) {
            this.key = key;
        }
    }
}
