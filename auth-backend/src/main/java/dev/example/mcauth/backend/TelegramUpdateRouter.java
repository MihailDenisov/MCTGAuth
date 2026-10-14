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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class TelegramUpdateRouter {
    private final TelegramClient telegram;
    private final String botUsername;
    private final long adminChatId;
    private final String howToPlayUrl;
    private final TelegramFeatureConfig features;
    private final String serverId;
    private final TicketService registrationTickets;
    private final SupportTicketService tickets;
    private final AccountService accounts;
    private final ModeratorService moderators;
    private final RemoteCommandService commands;
    private final ServerTelemetryService telemetry;
    private final TelegramUserModerationService userModeration;
    private final ModeratorPendingActionService pendingModeratorActions;
    private final LocalizationService localization;
    private final LocaleResolver locales;
    private final MenuActionRouter actions;
    private final TelegramKeyboards keyboards;
    private final Map<Long, FlowSession> sessions = new ConcurrentHashMap<>();

    TelegramUpdateRouter(
            TelegramClient telegram,
            String botUsername,
            long adminChatId,
            String howToPlayUrl,
            TelegramFeatureConfig features,
            String serverId,
            TicketService registrationTickets,
            SupportTicketService tickets,
            AccountService accounts,
            ModeratorService moderators,
            RemoteCommandService commands,
            ModeratorPendingActionService pendingModeratorActions,
            LocalizationService localization,
            LocaleResolver locales,
            ObjectMapper json) {
        this(telegram, botUsername, adminChatId, howToPlayUrl, features, serverId, registrationTickets,
                tickets, accounts, moderators, commands, pendingModeratorActions, null, null,
                localization, locales, json);
    }

    TelegramUpdateRouter(
            TelegramClient telegram, String botUsername, long adminChatId, String howToPlayUrl,
            TelegramFeatureConfig features, String serverId, TicketService registrationTickets,
            SupportTicketService tickets, AccountService accounts, ModeratorService moderators,
            RemoteCommandService commands, ModeratorPendingActionService pendingModeratorActions,
            ServerTelemetryService telemetry, TelegramUserModerationService userModeration,
            LocalizationService localization, LocaleResolver locales, ObjectMapper json) {
        this.telegram = telegram;
        this.botUsername = botUsername;
        this.adminChatId = adminChatId;
        this.howToPlayUrl = howToPlayUrl;
        this.features = Objects.requireNonNull(features, "features");
        this.serverId = serverId;
        this.registrationTickets = registrationTickets;
        this.tickets = tickets;
        this.accounts = accounts;
        this.moderators = moderators;
        this.commands = commands;
        this.telemetry = telemetry;
        this.userModeration = userModeration;
        this.pendingModeratorActions = pendingModeratorActions;
        this.localization = localization;
        this.locales = locales;
        this.actions = new MenuActionRouter(localization);
        this.keyboards = new TelegramKeyboards(json, localization, features);
    }

    void handle(JsonNode update) throws IOException, InterruptedException {
        if (update.has("message")) handleMessage(update.path("message"));
        else if (update.has("callback_query")) handleCallback(update.path("callback_query"));
        else if (update.has("chat_member")) handleChatMember(update.path("chat_member"));
    }

    private void handleChatMember(JsonNode update) {
        if (!features.memberTagsEnabled()
                || update.path("chat").path("id").asLong() != features.memberTagsChatId()) return;
        long telegramId = update.path("new_chat_member").path("user").path("id").asLong();
        if (telegramId <= 0) return;
        try {
            synchronizeChatTagSafely(accounts.chatTagPreference(telegramId));
        } catch (WorkflowException exception) {
            if (exception.code() != WorkflowException.Code.NOT_FOUND) throw exception;
        }
    }

    private void handleMessage(JsonNode message) throws IOException, InterruptedException {
        JsonNode from = message.path("from");
        long telegramId = from.path("id").asLong();
        long chatId = message.path("chat").path("id").asLong();
        String chatType = message.path("chat").path("type").asText("");
        boolean privateChat = "private".equals(chatType);
        boolean adminGroup = chatId == adminChatId && !privateChat;
        if (telegramId <= 0 || chatId == 0 || (!privateChat && !adminGroup)) return;
        String username = from.path("username").asText("");
        String text = message.path("text").asText("").strip();
        BotLocale suggestion = BotLocale.suggested(
                from.path("language_code").asText(""), locales.defaultUserLocale());
        boolean newUser = accounts.touchTelegramUser(telegramId, username, suggestion.code());
        BotLocale locale = adminGroup ? locales.adminLocale() : locales.userLocale(telegramId);
        Optional<BotAction> action = privateChat ? actions.resolve(text) : Optional.empty();
        String parsedCommand = command(text);
        if (text.startsWith("/") && parsedCommand.isEmpty()) return;

        if (userModeration != null && userModeration.isBlocked(telegramId)) {
            sessions.remove(telegramId);
            pendingModeratorActions.clear(telegramId);
            telegram.sendMessage(chatId, t(locale, MessageKey.BOT_ACCESS_RESTRICTED), null);
            return;
        }

        if (adminGroup) {
            if ("/menu".equals(parsedCommand) || "/adm".equals(parsedCommand)
                    || "/cancel".equals(parsedCommand)) {
                pendingModeratorActions.clear(telegramId);
                sessions.remove(telegramId);
                try {
                    requireModerator(telegramId);
                    MessageKey heading = "/cancel".equals(parsedCommand)
                            ? MessageKey.ACTION_CANCELLED : MessageKey.ADMIN_TITLE;
                    telegram.sendMessage(chatId, t(locale, heading),
                            keyboards.adminInlineMenu(locale));
                } catch (WorkflowException exception) {
                    telegram.sendMessage(chatId, localizedError(locale, exception), null);
                }
            }
            return;
        }

        if ("/cancel".equals(parsedCommand) || action.orElse(null) == BotAction.CANCEL) {
            boolean administrative = isAdministrativeContext(telegramId);
            sessions.remove(telegramId);
            pendingModeratorActions.clear(telegramId);
            if (administrative && moderators.isEnabled(telegramId)) {
                telegram.sendMessage(chatId, t(locale, MessageKey.ACTION_CANCELLED),
                        keyboards.adminMenu(locale));
            } else {
                showMainMenu(chatId, telegramId, MessageKey.ACTION_CANCELLED);
            }
            return;
        }
        try {
            if (routeCommandOrMenu(chatId, telegramId, username, text, action.orElse(null), newUser, locale)) return;
        } catch (WorkflowException | IllegalArgumentException exception) {
            telegram.sendMessage(chatId, localizedError(locale, exception), null);
            return;
        }
        if (text.startsWith("/")) {
            telegram.sendMessage(chatId, t(locale, MessageKey.ERROR_UNKNOWN_ACTION), null);
            return;
        }

        FlowSession session = sessions.get(telegramId);
        Optional<ModeratorPendingAction> pending = pendingModeratorActions.find(telegramId);
        if (session == null && pending.isPresent()) {
            try {
                completeModeratorPending(chatId, telegramId, username, text, pending.get(), locale);
            } catch (WorkflowException | IllegalArgumentException exception) {
                if (exception instanceof WorkflowException workflow
                        && (workflow.code() == WorkflowException.Code.INVALID_STATE
                        || workflow.code() == WorkflowException.Code.NOT_FOUND
                        || workflow.code() == WorkflowException.Code.UNAUTHORIZED)) {
                    pendingModeratorActions.clear(telegramId);
                }
                telegram.sendMessage(chatId, localizedError(locale, exception),
                        keyboards.adminMenu(locale));
            }
            return;
        }
        if (session == null) {
            showRelevantMenu(chatId, telegramId, MessageKey.MAIN_CHOOSE_ACTION);
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
        if ("/start".equals(command) || "/menu".equals(command) || action == BotAction.MAIN_MENU) {
            sessions.remove(telegramId);
            pendingModeratorActions.clear(telegramId);
            if (newUser && "/start".equals(command)) showLanguageSelector(chatId);
            else showMainMenu(chatId, telegramId, MessageKey.MAIN_WELCOME);
            return true;
        }
        if ("/adm".equals(command) || "/admin".equals(command)
                || action == BotAction.ADMINISTRATION || action == BotAction.ADMIN_MENU) {
            sessions.remove(telegramId);
            pendingModeratorActions.clear(telegramId);
            requireModerator(telegramId);
            telegram.sendMessage(chatId, t(locale, MessageKey.ADMIN_TITLE), keyboards.adminMenu(locale));
            return true;
        }
        if (action == BotAction.BACK) {
            Optional<ModeratorPendingAction> pending = pendingModeratorActions.find(telegramId);
            if (pending.isPresent() && moderators.isEnabled(telegramId)) {
                pendingModeratorActions.clear(telegramId);
                showAdminTicket(chatId, new ModeratorIdentity(telegramId, username),
                        pending.get().ticketId(), locale);
                return true;
            }
            handleBack(chatId, telegramId, locale);
            return true;
        }
        if ("/register".equals(command) || action == BotAction.REGISTER) {
            beginRegistrationWithSubscriptionGate(chatId, telegramId, username, locale);
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
            showHowToPlay(chatId, telegramId, locale);
            return true;
        }
        if (action == BotAction.SERVER_INFORMATION) {
            requireRegistered(telegramId);
            sessions.put(telegramId, new FlowSession(FlowKind.SERVER_INFORMATION));
            telegram.sendMessage(chatId, t(locale, MessageKey.SERVER_INFORMATION_TITLE),
                    keyboards.serverInformationMenu(locale));
            return true;
        }
        if (action == BotAction.SERVER_STATUS && features.serverStatusEnabled()) {
            showServerStatus(chatId, locale);
            return true;
        }
        if (action == BotAction.SERVER_MAP && features.serverMapUrl() != null) {
            showServerMap(chatId, locale);
            return true;
        }
        if (action == BotAction.MODPACK_DOWNLOAD && features.modpackDownloadUrl() != null) {
            showModpackDownload(chatId, locale);
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
            telegram.sendMessage(chatId, t(locale, MessageKey.SETTINGS_TITLE), keyboards.settingsMenu(locale));
            return true;
        }
        if (action == BotAction.ADD_ACCOUNT) {
            requireRegistered(telegramId);
            sessions.put(telegramId, new FlowSession(FlowKind.ADD_NICKNAME));
            telegram.sendMessage(chatId, t(locale, MessageKey.ADD_NICKNAME), keyboards.wizard(locale, false));
            return true;
        }
        if (action == BotAction.CHANGE_PASSWORD) {
            sessions.remove(telegramId);
            showPasswordChoices(chatId, telegramId, locale);
            return true;
        }
        if (action == BotAction.MY_PLAYTIME) {
            showPlaytimeChoices(chatId, telegramId, locale);
            return true;
        }
        return moderators.isEnabled(telegramId)
                && routeAdminMenu(chatId, telegramId, username, text, command, action, locale);
    }

    private boolean routeAdminMenu(
            long chatId, long telegramId, String username, String text, String command,
            BotAction action, BotLocale locale) throws IOException, InterruptedException {
        ModeratorIdentity moderator = new ModeratorIdentity(telegramId, username);
        if (action == BotAction.REQUESTS) {
            telegram.sendMessage(chatId, t(locale, MessageKey.MENU_REQUESTS), keyboards.requestsMenu(locale));
            return true;
        }
        if (action == BotAction.PLAYER_STATISTICS) {
            showPlayerStatistics(chatId, moderator, 0, locale);
            return true;
        }
        if (action == BotAction.NEW_TICKETS) {
            showAdminTickets(chatId, moderator, TicketStatus.OPEN, null, locale); return true;
        }
        if (action == BotAction.IN_PROGRESS) {
            showAdminTickets(chatId, moderator, TicketStatus.IN_PROGRESS, null, locale); return true;
        }
        if (action == BotAction.MY_ASSIGNED) {
            showAdminTickets(chatId, moderator, null, null, moderator.telegramId(), 0, "mine", locale);
            return true;
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
            telegram.sendMessage(chatId, t(locale, MessageKey.ADMIN_SEARCH_PROMPT),
                    keyboards.wizard(locale, true));
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
            telegram.sendMessage(chatId, t(locale, MessageKey.MANUAL_OWNER),
                    keyboards.wizard(locale, true)); return true;
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
            telegram.sendMessage(chatId, t(locale, MessageKey.SERVER_RESYNC_ACCOUNT),
                    keyboards.wizard(locale, true));
            return true;
        }
        if (action == BotAction.SYNC_MEMBER_TAGS) {
            moderators.requirePermission(moderator, ModeratorPermission.TELEGRAM_MEMBER_TAG_SYNC);
            TagSyncStatistics statistics = synchronizeAllChatTags();
            telegram.sendMessage(chatId, t(locale, MessageKey.CHAT_TAG_SYNC_RESULT,
                    statistics.processed(), statistics.updated(), statistics.cleared(),
                    statistics.skippedAdministrators(), statistics.notMembers(), statistics.failed()),
                    keyboards.serverMenu(locale));
            return true;
        }
        return false;
    }

    private void advance(
            long chatId, long telegramId, String username, String text, JsonNode message,
            FlowSession session, BotAction action, BotLocale locale)
            throws IOException, InterruptedException {
        switch (session.kind) {
            case SERVER_INFORMATION -> {
                sessions.remove(telegramId);
                showMainMenu(chatId, telegramId, MessageKey.MAIN_CHOOSE_ACTION);
            }
            case REG_NICKNAME -> {
                session.put("nickname", text); session.kind = FlowKind.REG_LICENSE;
                yesNo(chatId, locale, MessageKey.REG_LICENSE);
            }
            case REG_LICENSE -> {
                session.put("licensed", parseYesNo(action)); session.kind = FlowKind.REG_AGE;
                telegram.sendMessage(chatId, t(locale, MessageKey.REG_AGE), keyboards.wizard(locale, false));
            }
            case REG_AGE -> {
                int age = Integer.parseInt(text);
                if (age < 1 || age > 120) throw new LocalizedUiException(MessageKey.REG_AGE_INVALID);
                session.put("age", text); session.kind = FlowKind.REG_EXPERIENCE;
                telegram.sendMessage(chatId, t(locale, MessageKey.REG_EXPERIENCE),
                        keyboards.wizard(locale, false));
            }
            case REG_EXPERIENCE -> {
                session.put("experience", text); session.kind = FlowKind.REG_CONFIRM;
                yesNo(chatId, locale, MessageKey.REG_CONFIRM,
                        username == null || username.isBlank() ? Long.toString(telegramId) : "@" + username,
                        session.get("nickname"), yesNoLabel(locale, Boolean.parseBoolean(session.get("licensed"))),
                        session.get("age"), session.get("experience"));
            }
            case REG_CONFIRM -> finishRegistration(
                    chatId, telegramId, username, session, parseYesNo(action), locale);
            case PROBLEM_ACCOUNT -> {
                session.put("accountId", positiveLong(text)); session.kind = FlowKind.PROBLEM_DESCRIPTION;
                telegram.sendMessage(chatId, t(locale, MessageKey.PROBLEM_DESCRIPTION),
                        keyboards.wizard(locale, false));
            }
            case PROBLEM_DESCRIPTION -> {
                session.put("description", text); session.kind = FlowKind.PROBLEM_ATTACHMENTS;
                telegram.sendMessage(chatId, t(locale, MessageKey.PROBLEM_ATTACHMENTS),
                        keyboards.attachmentMenu(locale));
            }
            case PROBLEM_ATTACHMENTS ->
                    collectOrSubmit(chatId, telegramId, username, message, session, false, action, locale);
            case VIOLATION_ACCOUNT -> {
                session.put("accountId", positiveLong(text)); session.kind = FlowKind.VIOLATION_OFFENDER_KNOWN;
                telegram.sendMessage(chatId, t(locale, MessageKey.VIOLATION_OFFENDER_KNOWN),
                        keyboards.offenderKnown(locale));
            }
            case VIOLATION_OFFENDER_KNOWN -> {
                if (action == BotAction.YES) {
                    session.kind = FlowKind.VIOLATION_OFFENDER;
                    telegram.sendMessage(chatId, t(locale, MessageKey.VIOLATION_OFFENDER),
                            keyboards.wizard(locale, false));
                } else if (action == BotAction.UNKNOWN) {
                    session.put("offender", "");
                    session.kind = FlowKind.VIOLATION_DESCRIPTION;
                    telegram.sendMessage(chatId, t(locale, MessageKey.VIOLATION_DESCRIPTION),
                            keyboards.wizard(locale, false));
                } else {
                    throw new LocalizedUiException(MessageKey.ERROR_INVALID_INPUT);
                }
            }
            case VIOLATION_OFFENDER -> {
                session.put("offender", text); session.kind = FlowKind.VIOLATION_DESCRIPTION;
                telegram.sendMessage(chatId, t(locale, MessageKey.VIOLATION_DESCRIPTION),
                        keyboards.wizard(locale, false));
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
                telegram.sendMessage(chatId, t(locale, MessageKey.MANUAL_NICKNAME),
                        keyboards.wizard(locale, true));
            }
            case MANUAL_NICKNAME -> {
                session.put("nickname", text); session.kind = FlowKind.MANUAL_LICENSE;
                yesNoAdmin(chatId, locale, MessageKey.MANUAL_LICENSE);
            }
            case MANUAL_LICENSE -> {
                session.put("licensed", parseYesNo(action)); session.kind = FlowKind.MANUAL_CONFIRM;
                yesNoAdmin(chatId, locale, MessageKey.MANUAL_CONFIRM);
            }
            case MANUAL_CONFIRM ->
                    finishManual(chatId, telegramId, username, session, parseYesNo(action), locale);
            case USER_REPLY -> {
                long ticketId = Long.parseLong(session.get("ticketId"));
                tickets.userReply(telegramId, ticketId, text);
                sessions.remove(telegramId);
                telegram.sendMessage(adminChatId,
                        t(locales.adminLocale(), MessageKey.TICKET_USER_REPLIED_ADMIN,
                                Long.toString(ticketId), text), null);
                showMainMenu(chatId, telegramId, MessageKey.TICKET_REPLY_SENT,
                        Long.toString(ticketId));
            }
            case ADMIN_SEARCH -> {
                sessions.remove(telegramId);
                showSearch(chatId, new ModeratorIdentity(telegramId, username), text, locale);
            }
            case ADMIN_DISABLE_REASON -> {
                session.put("reason", text);
                session.kind = FlowKind.ADMIN_DISABLE_CONFIRM;
                ModeratorIdentity moderator = new ModeratorIdentity(telegramId, username);
                AccountView account = accounts.accountForModerator(
                        moderator, Long.parseLong(session.get("accountId")));
                showDisableConfirmation(chatId, account, locale);
            }
            case ADMIN_DISABLE_CONFIRM -> throw new LocalizedUiException(MessageKey.ERROR_INVALID_STATE);
            case ADMIN_BOT_BLOCK_REASON -> {
                session.put("reason", text);
                session.kind = FlowKind.ADMIN_BOT_BLOCK_CONFIRM;
                showBotBlockConfirmation(chatId, Long.parseLong(session.get("targetId")),
                        session.get("confirmationData"), locale);
            }
            case ADMIN_BOT_BLOCK_CONFIRM -> throw new LocalizedUiException(MessageKey.ERROR_INVALID_STATE);
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
        if (userModeration != null && userModeration.isBlocked(telegramId)) {
            sessions.remove(telegramId);
            pendingModeratorActions.clear(telegramId);
            telegram.sendMessage(chatId, t(locale, MessageKey.BOT_ACCESS_RESTRICTED), null);
            telegram.answerCallback(callbackId, t(locale, MessageKey.BOT_ACCESS_RESTRICTED));
            return;
        }
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
            if ("registration:subscription:retry".equals(data)) {
                if (!"private".equals(chatType)) throw new WorkflowException(
                        WorkflowException.Code.UNAUTHORIZED, "Private chat required");
                beginRegistrationWithSubscriptionGate(chatId, telegramId, username, locale);
                telegram.answerCallback(callbackId, t(locale, MessageKey.CALLBACK_DONE));
                return;
            }
            if (data.startsWith("tickets:page:")) {
                showMyTickets(chatId, telegramId,
                        Integer.parseInt(data.substring("tickets:page:".length())), locale);
            } else if (data.startsWith("server_info:")) {
                handleServerInformationCallback(chatId, telegramId, data, locale);
            } else if (data.startsWith("chat_tag:")) {
                handleChatTagCallback(chatId, telegramId, chatType, data, locale);
            } else if (data.startsWith("ticket:")) {
                handleTicketCallback(callbackId, chatId, messageId, telegramId, username, data, locale);
                return;
            } else if (data.startsWith("admin-account:")) {
                handleAdminAccountCallback(callbackId, chatId, telegramId, username, chatType, data, locale);
                return;
            } else if (data.startsWith("admin-user:")) {
                handleAdminUserCallback(callbackId, chatId, telegramId, username, chatType, data, locale);
                return;
            } else if (data.startsWith("account:")) {
                handleAccountCallback(callbackId, chatId, telegramId, data, locale);
                return;
            } else if ("server:players".equals(data)) {
                showPlayerList(chatId, locale);
            } else if (data.startsWith("players:stats:")) {
                requireModerator(telegramId);
                showPlayerStatistics(chatId, new ModeratorIdentity(telegramId, username),
                        Integer.parseInt(data.substring("players:stats:".length())), locale);
            } else if ("nav:requests".equals(data)) {
                requireModerator(telegramId);
                telegram.sendMessage(chatId, t(locale, MessageKey.MENU_REQUESTS),
                        chatId == adminChatId ? keyboards.requestsInlineMenu(locale) : keyboards.requestsMenu(locale));
            } else if (data.startsWith("admin:list:")) {
                requireModerator(telegramId);
                String[] parts = data.split(":");
                if (parts.length != 4) throw new LocalizedUiException(MessageKey.ERROR_UNKNOWN_ACTION);
                showAdminFilter(chatId, new ModeratorIdentity(telegramId, username),
                        parts[2], Integer.parseInt(parts[3]), locale);
            } else if ("admin:search".equals(data)) {
                requireModerator(telegramId);
                sessions.put(telegramId, new FlowSession(FlowKind.ADMIN_SEARCH));
                if (!"private".equals(chatType)) {
                    telegram.sendMessage(telegramId, t(locales.userLocale(telegramId),
                            MessageKey.ADMIN_SEARCH_PROMPT),
                            keyboards.wizard(locales.userLocale(telegramId), true));
                } else {
                    sessions.put(telegramId, new FlowSession(FlowKind.ADMIN_SEARCH));
                    telegram.sendMessage(chatId, t(locale, MessageKey.ADMIN_SEARCH_PROMPT),
                            keyboards.wizard(locale, true));
                }
            } else if ("nav:main".equals(data)) {
                sessions.remove(telegramId);
                pendingModeratorActions.clear(telegramId);
                showMainMenu(chatId, telegramId, MessageKey.MAIN_CHOOSE_ACTION);
            } else if ("nav:admin".equals(data)) {
                requireModerator(telegramId);
                sessions.remove(telegramId);
                pendingModeratorActions.clear(telegramId);
                if (chatId == adminChatId) {
                    telegram.sendMessage(chatId, t(locale, MessageKey.ADMIN_TITLE),
                            keyboards.adminInlineMenu(locale));
                } else {
                    telegram.sendMessage(chatId, t(locale, MessageKey.ADMIN_TITLE),
                            keyboards.adminMenu(locale));
                }
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
            if (exception instanceof WorkflowException workflow
                    && (workflow.code() == WorkflowException.Code.INVALID_STATE
                    || workflow.code() == WorkflowException.Code.NOT_FOUND)
                    && data.startsWith("ticket:")) {
                String[] parts = data.split(":");
                String ticketId = parts.length >= 2 ? parts[1] : "0";
                telegram.sendMessage(chatId, t(locale, MessageKey.TICKET_STALE),
                        keyboards.inline(new String[][]{
                                {t(locale, MessageKey.BUTTON_REFRESH), "ticket:" + ticketId + ":refresh"},
                                {t(locale, MessageKey.BUTTON_ADMIN_MENU), "nav:admin"}
                        }));
            } else if (exception instanceof WorkflowException workflow
                    && (workflow.code() == WorkflowException.Code.INVALID_STATE
                    || workflow.code() == WorkflowException.Code.NOT_FOUND
                    || workflow.code() == WorkflowException.Code.UNAUTHORIZED)
                    && (data.startsWith("admin-account:") || data.startsWith("account:"))) {
                boolean adminAction = data.startsWith("admin-account:");
                telegram.sendMessage(chatId, t(locale, MessageKey.STALE_ACTION),
                        keyboards.inline(new String[][]{{t(locale,
                                adminAction ? MessageKey.BUTTON_ADMIN_MENU : MessageKey.BUTTON_MAIN_MENU),
                                adminAction ? "nav:admin" : "nav:main"}}));
            }
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
                    t(locale, MessageKey.TICKET_REPLY_PROMPT, Long.toString(ticketId)),
                    keyboards.wizard(locale, false));
        } else {
            requireModerator(telegramId);
            ModeratorIdentity moderator = new ModeratorIdentity(telegramId, username);
            switch (action) {
                case "refresh" -> showAdminTicket(chatId, moderator, ticketId, locale);
                case "take" -> {
                    tickets.claim(ticketId, moderator);
                    telegram.editMessage(chatId, messageId,
                            t(locale, MessageKey.TICKET_ADMIN_TAKEN,
                                    Long.toString(ticketId), formatIdentity(telegramId, username, locale)));
                    showAdminTicket(chatId, moderator, ticketId, locale);
                }
                case "reply" -> {
                    TicketDetail detail = tickets.ticketForModerator(moderator, ticketId);
                    pendingModeratorActions.beginReply(moderator, ticketId);
                    sessions.remove(telegramId);
                    BotLocale moderatorLocale = locales.userLocale(telegramId);
                    telegram.sendMessage(telegramId,
                            t(moderatorLocale, MessageKey.TICKET_REPLY_CONTEXT,
                                    Long.toString(ticketId), formatIdentity(detail.ticket(), moderatorLocale),
                                    ticketMinecraft(detail)),
                            keyboards.wizard(moderatorLocale, true));
                }
                case "resolve" -> {
                    showClosureChoice(chatId, ticketId, TicketStatus.RESOLVED, locale);
                }
                case "close" -> showClosureChoice(chatId, ticketId, TicketStatus.CLOSED, locale);
                case "resolve_comment" -> beginClosureComment(
                        telegramId, moderator, ticketId, TicketStatus.RESOLVED, locale);
                case "close_comment" -> beginClosureComment(
                        telegramId, moderator, ticketId, TicketStatus.CLOSED, locale);
                case "resolve_now" -> finishTicketClosure(
                        chatId, messageId, ticketId, moderator, TicketStatus.RESOLVED, null, locale);
                case "close_now" -> finishTicketClosure(
                        chatId, messageId, ticketId, moderator, TicketStatus.CLOSED, null, locale);
                case "closure_cancel" -> {
                    pendingModeratorActions.clear(telegramId);
                    showAdminTicket(chatId, moderator, ticketId, locale);
                }
                case "reject" -> beginRejectionChoice(chatId, messageId, ticketId, moderator, locale);
                case "reject_reason" -> beginRegistrationRejectionReason(
                        chatId, moderator, ticketId, locale);
                case "reject_now" -> rejectTicket(
                        chatId, messageId, ticketId, moderator, locale, "");
                case "reject_cancel" -> showAdminTicket(chatId, moderator, ticketId, locale);
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
                            {t(locale, MessageKey.BUTTON_CANCEL), "account:" + accountId + ":cancel"},
                            {t(locale, MessageKey.BUTTON_MAIN_MENU), "nav:main"}}));
        } else if ("password_confirm".equals(parts[2])) {
            try (CredentialIssueResult result = accounts.changePassword(telegramId, accountId)) {
                sendCredential(chatId, result, locale);
            }
        } else if ("playtime".equals(parts[2])) {
            showPlaytime(chatId, telegramId, accountId, locale);
        } else if ("cancel".equals(parts[2])) {
            showAccount(chatId, telegramId, locale);
        } else {
            throw new LocalizedUiException(MessageKey.ERROR_UNKNOWN_ACTION);
        }
        telegram.answerCallback(callbackId, t(locale, MessageKey.CALLBACK_DONE));
    }

    private void handleChatTagCallback(
            long chatId, long telegramId, String chatType, String data, BotLocale locale)
            throws IOException, InterruptedException {
        if (!"private".equals(chatType)) {
            throw new WorkflowException(WorkflowException.Code.UNAUTHORIZED, "Private chat required");
        }
        if (!features.memberTagsEnabled()) {
            telegram.sendMessage(chatId, t(locale, MessageKey.CHAT_TAG_UNAVAILABLE), keyboards.settingsMenu(locale));
            return;
        }
        if ("chat_tag:open".equals(data)) {
            showChatTagChoices(chatId, telegramId, locale);
            return;
        }
        if ("chat_tag:none".equals(data)) {
            ChatTagPreference preference = accounts.selectChatTag(telegramId, null);
            synchronizeChatTagSafely(preference);
            telegram.sendMessage(chatId, t(locale, MessageKey.CHAT_TAG_SAVED,
                    t(locale, MessageKey.CHAT_TAG_NO_TAG)), keyboards.settingsMenu(locale));
            return;
        }
        if (data.startsWith("chat_tag:account:")) {
            long accountId = Long.parseLong(data.substring("chat_tag:account:".length()));
            ChatTagPreference preference = accounts.selectChatTag(telegramId, accountId);
            synchronizeChatTagSafely(preference);
            telegram.sendMessage(chatId, t(locale, MessageKey.CHAT_TAG_SAVED,
                    preference.minecraftName()), keyboards.settingsMenu(locale));
            return;
        }
        throw new LocalizedUiException(MessageKey.ERROR_UNKNOWN_ACTION);
    }

    private void showChatTagChoices(long chatId, long telegramId, BotLocale locale)
            throws IOException, InterruptedException {
        List<String[]> buttons = new ArrayList<>();
        accounts.accountsFor(telegramId).stream().filter(AccountView::enabled).forEach(account ->
                buttons.add(new String[]{account.nickname(), "chat_tag:account:" + account.id()}));
        buttons.add(new String[]{t(locale, MessageKey.CHAT_TAG_NO_TAG), "chat_tag:none"});
        buttons.add(new String[]{t(locale, MessageKey.BUTTON_BACK), "nav:main"});
        telegram.sendMessage(chatId, t(locale, MessageKey.CHAT_TAG_TITLE),
                keyboards.inline(buttons.toArray(String[][]::new)));
    }

    private void beginRejectionChoice(
            long chatId, long messageId, long ticketId, ModeratorIdentity moderator, BotLocale locale)
            throws IOException, InterruptedException {
        TicketDetail detail = tickets.ticketForModerator(moderator, ticketId);
        if (detail.ticket().type() != TicketType.REGISTRATION) {
            rejectTicket(chatId, messageId, ticketId, moderator, locale, "");
            return;
        }
        telegram.sendMessage(chatId, t(locale, MessageKey.TICKET_REJECTION_QUESTION),
                keyboards.inline(new String[][]{
                        {t(locale, MessageKey.BUTTON_ADD_REASON), "ticket:" + ticketId + ":reject_reason"},
                        {t(locale, MessageKey.BUTTON_REJECT_WITHOUT_REASON),
                                "ticket:" + ticketId + ":reject_now"},
                        {t(locale, MessageKey.BUTTON_CANCEL), "ticket:" + ticketId + ":reject_cancel"}
                }));
    }

    private void beginRegistrationRejectionReason(
            long moderatorChatId, ModeratorIdentity moderator, long ticketId, BotLocale locale)
            throws IOException, InterruptedException {
        pendingModeratorActions.beginRegistrationRejectionReason(moderator, ticketId);
        sessions.remove(moderator.telegramId());
        BotLocale moderatorLocale = locales.userLocale(moderator.telegramId());
        telegram.sendMessage(moderator.telegramId(),
                t(moderatorLocale, MessageKey.TICKET_REJECTION_REASON_PROMPT, Long.toString(ticketId)),
                keyboards.wizard(moderatorLocale, true));
        if (moderatorChatId != moderator.telegramId()) {
            telegram.sendMessage(moderatorChatId,
                    t(locale, MessageKey.TICKET_REJECTION_REASON_PROMPT, Long.toString(ticketId)), null);
        }
    }

    private TagSyncOutcome synchronizeChatTagSafely(ChatTagPreference preference) {
        if (!features.memberTagsEnabled()) return TagSyncOutcome.FAILED;
        try {
            TelegramChatMember member = telegram.getChatMember(
                    features.memberTagsChatId(), preference.telegramUserId());
            if (!member.isMember()) return TagSyncOutcome.NOT_MEMBER;
            if (member.isAdministrator()) {
                return TagSyncOutcome.SKIPPED_ADMINISTRATOR;
            }
            String desired = preference.hasTag() ? preference.minecraftName() : "";
            if (desired.equals(member.tag())) return TagSyncOutcome.UNCHANGED;
            telegram.setChatMemberTag(features.memberTagsChatId(), preference.telegramUserId(), desired);
            return desired.isEmpty() ? TagSyncOutcome.CLEARED : TagSyncOutcome.UPDATED;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            System.err.println("Telegram member tag synchronization interrupted");
            return TagSyncOutcome.FAILED;
        } catch (IOException | RuntimeException exception) {
            System.err.println("Telegram member tag synchronization failed ("
                    + exception.getClass().getSimpleName() + ")");
            return TagSyncOutcome.FAILED;
        }
    }

    private TagSyncStatistics synchronizeAllChatTags() {
        int processed = 0;
        int updated = 0;
        int cleared = 0;
        int skippedAdministrators = 0;
        int notMembers = 0;
        int failed = 0;
        for (ChatTagPreference preference : accounts.chatTagPreferences()) {
            processed++;
            switch (synchronizeChatTagSafely(preference)) {
                case UPDATED -> updated++;
                case CLEARED -> cleared++;
                case SKIPPED_ADMINISTRATOR -> skippedAdministrators++;
                case NOT_MEMBER -> notMembers++;
                case FAILED -> failed++;
                case UNCHANGED -> { }
            }
        }
        return new TagSyncStatistics(processed, updated, cleared, skippedAdministrators, notMembers, failed);
    }

    private void showClosureChoice(
            long chatId, long ticketId, TicketStatus target, BotLocale locale)
            throws IOException, InterruptedException {
        String prefix = target == TicketStatus.CLOSED ? "close" : "resolve";
        telegram.sendMessage(chatId, t(locale, MessageKey.TICKET_CLOSURE_QUESTION),
                keyboards.inline(new String[][]{
                        {t(locale, MessageKey.BUTTON_ADD_COMMENT),
                                "ticket:" + ticketId + ":" + prefix + "_comment"},
                        {t(locale, MessageKey.BUTTON_CLOSE_WITHOUT_COMMENT),
                                "ticket:" + ticketId + ":" + prefix + "_now"},
                        {t(locale, MessageKey.BUTTON_CANCEL),
                                "ticket:" + ticketId + ":closure_cancel"}
                }));
    }

    private void beginClosureComment(
            long moderatorChatId, ModeratorIdentity moderator, long ticketId,
            TicketStatus target, BotLocale locale) throws IOException, InterruptedException {
        try {
            pendingModeratorActions.beginClosureComment(moderator, ticketId, target);
        } catch (WorkflowException exception) {
            if (exception.code() == WorkflowException.Code.INVALID_STATE) {
                telegram.sendMessage(moderatorChatId, t(locale, MessageKey.TICKET_ALREADY_CLOSED),
                        keyboards.inline(new String[][]{
                                {t(locale, MessageKey.BUTTON_REFRESH), "ticket:" + ticketId + ":refresh"},
                                {t(locale, MessageKey.BUTTON_ADMIN_MENU), "nav:admin"}
                        }));
                return;
            }
            throw exception;
        }
        sessions.remove(moderator.telegramId());
        BotLocale moderatorLocale = locales.userLocale(moderator.telegramId());
        telegram.sendMessage(moderator.telegramId(),
                t(moderatorLocale, MessageKey.TICKET_CLOSURE_COMMENT_PROMPT, Long.toString(ticketId)),
                keyboards.wizard(moderatorLocale, true));
        if (moderatorChatId != moderator.telegramId()) {
            telegram.sendMessage(moderatorChatId,
                    t(locale, MessageKey.TICKET_CLOSURE_COMMENT_PROMPT, Long.toString(ticketId)), null);
        }
    }

    private void finishTicketClosure(
            long chatId, long messageId, long ticketId, ModeratorIdentity moderator,
            TicketStatus target, String comment, BotLocale locale)
            throws IOException, InterruptedException {
        try {
            tickets.closeWithOptionalComment(ticketId, moderator, target, comment);
        } catch (WorkflowException exception) {
            if (exception.code() == WorkflowException.Code.INVALID_STATE) {
                telegram.sendMessage(chatId, t(locale, MessageKey.TICKET_ALREADY_CLOSED),
                        keyboards.inline(new String[][]{
                                {t(locale, MessageKey.BUTTON_REFRESH), "ticket:" + ticketId + ":refresh"},
                                {t(locale, MessageKey.BUTTON_ADMIN_MENU), "nav:admin"}
                        }));
                return;
            }
            throw exception;
        }
        TicketDetail detail = tickets.ticketForModerator(moderator, ticketId);
        BotLocale userLocale = locales.userLocale(detail.ticket().creatorTelegramId());
        MessageKey notification = comment == null || comment.isBlank()
                ? MessageKey.TICKET_CLOSED_USER : MessageKey.TICKET_CLOSED_USER_WITH_COMMENT;
        telegram.sendMessage(detail.ticket().creatorTelegramId(),
                comment == null || comment.isBlank()
                        ? t(userLocale, notification) : t(userLocale, notification, comment), null);
        if (messageId > 0) {
            telegram.editMessage(chatId, messageId,
                    t(locale, MessageKey.TICKET_ADMIN_TERMINAL,
                            Long.toString(ticketId), ticketStatus(locale, target)));
        }
        showAdminTicket(chatId, moderator, ticketId, locale);
    }

    private void handleAdminAccountCallback(
            String callbackId, long chatId, long telegramId, String username, String chatType,
            String data, BotLocale locale) throws IOException, InterruptedException {
        if (!"private".equals(chatType)) {
            throw new WorkflowException(WorkflowException.Code.UNAUTHORIZED, "Private chat required");
        }
        requireModerator(telegramId);
        String[] parts = data.split(":");
        if (parts.length != 3) throw new LocalizedUiException(MessageKey.ERROR_UNKNOWN_ACTION);
        long accountId = Long.parseLong(parts[1]);
        ModeratorIdentity moderator = new ModeratorIdentity(telegramId, username);
        switch (parts[2]) {
            case "view" -> showAdminAccount(chatId, moderator, accountId, locale);
            case "disable" -> {
                AccountView account = accounts.accountForModerator(moderator, accountId);
                if (!account.enabled()) {
                    telegram.sendMessage(chatId, t(locale, MessageKey.ACCOUNT_ALREADY_DISABLED,
                            account.nickname()), accountAdminKeyboard(account, locale));
                } else {
                    FlowSession session = new FlowSession(FlowKind.ADMIN_DISABLE_REASON);
                    session.put("accountId", accountId);
                    session.put("reason", "");
                    sessions.put(telegramId, session);
                    telegram.sendMessage(chatId, t(locale, MessageKey.ACCOUNT_DISABLE_REASON,
                            account.nickname()), keyboards.inline(new String[][]{
                            {t(locale, MessageKey.BUTTON_SKIP), "admin-account:" + accountId + ":disable_skip"},
                            {t(locale, MessageKey.BUTTON_CANCEL), "admin-account:" + accountId + ":disable_cancel"},
                            {t(locale, MessageKey.BUTTON_MAIN_MENU), "nav:main"}
                    }));
                }
            }
            case "disable_skip" -> {
                FlowSession session = requireDisableSession(telegramId, accountId);
                session.put("reason", "");
                session.kind = FlowKind.ADMIN_DISABLE_CONFIRM;
                showDisableConfirmation(chatId, accounts.accountForModerator(moderator, accountId), locale);
            }
            case "disable_confirm" -> {
                FlowSession session = requireDisableSession(telegramId, accountId);
                AccountStateChangeResult result = accounts.disable(moderator, accountId, session.get("reason"));
                sessions.remove(telegramId);
                if (result.changed()) {
                    synchronizeChatTagSafely(accounts.chatTagPreference(result.account().ownerTelegramId()));
                }
                sendAccountStateResult(chatId, result, false, locale);
            }
            case "enable" -> sendAccountStateResult(chatId,
                    accounts.enable(moderator, accountId, ""), true, locale);
            case "bot_block" -> {
                AccountView account = accounts.accountForModerator(moderator, accountId);
                FlowSession session = new FlowSession(FlowKind.ADMIN_BOT_BLOCK_REASON);
                session.put("targetId", account.ownerTelegramId());
                session.put("confirmationData", "admin-account:" + accountId + ":bot_block_confirm");
                session.put("reason", "");
                sessions.put(telegramId, session);
                telegram.sendMessage(chatId, t(locale, MessageKey.BOT_BLOCK_REASON), keyboards.inline(new String[][]{
                        {t(locale, MessageKey.BUTTON_SKIP), "admin-account:" + accountId + ":bot_block_skip"},
                        {t(locale, MessageKey.BUTTON_CANCEL), "admin-account:" + accountId + ":view"}}));
            }
            case "bot_block_skip" -> {
                AccountView account = accounts.accountForModerator(moderator, accountId);
                FlowSession session = requireBotBlockSession(telegramId, account.ownerTelegramId());
                session.kind = FlowKind.ADMIN_BOT_BLOCK_CONFIRM;
                showBotBlockConfirmation(chatId, account.ownerTelegramId(),
                        "admin-account:" + accountId + ":bot_block_confirm", locale);
            }
            case "bot_unblock" -> {
                AccountView account = accounts.accountForModerator(moderator, accountId);
                telegram.sendMessage(chatId, t(locale, MessageKey.BOT_UNBLOCK_CONFIRM,
                        Long.toString(account.ownerTelegramId())), keyboards.inline(new String[][]{
                        {t(locale, MessageKey.BUTTON_CONFIRM), "admin-account:" + accountId + ":bot_unblock_confirm"},
                        {t(locale, MessageKey.BUTTON_CANCEL), "admin-account:" + accountId + ":view"}}));
            }
            case "bot_block_confirm", "bot_unblock_confirm" -> {
                AccountView account = accounts.accountForModerator(moderator, accountId);
                boolean blocked = "bot_block_confirm".equals(parts[2]);
                String reason = blocked
                        ? requireBotBlockSession(telegramId, account.ownerTelegramId()).get("reason") : "";
                TelegramUserModerationService.ChangeResult result = userModeration.setBlocked(
                        moderator, account.ownerTelegramId(), blocked, reason);
                sessions.remove(telegramId);
                telegram.sendMessage(chatId, t(locale, blocked ? MessageKey.BOT_BLOCKED_DONE
                        : MessageKey.BOT_UNBLOCKED_DONE, Long.toString(result.targetTelegramId())),
                        accountAdminKeyboard(account, locale));
            }
            case "disable_cancel" -> {
                sessions.remove(telegramId);
                showAdminAccount(chatId, moderator, accountId, locale);
            }
            default -> throw new LocalizedUiException(MessageKey.ERROR_UNKNOWN_ACTION);
        }
        telegram.answerCallback(callbackId, t(locale, MessageKey.CALLBACK_DONE));
    }

    private void handleAdminUserCallback(
            String callbackId, long chatId, long telegramId, String username, String chatType,
            String data, BotLocale locale) throws IOException, InterruptedException {
        requireModerator(telegramId);
        String[] parts = data.split(":");
        if (parts.length != 3) throw new LocalizedUiException(MessageKey.ERROR_UNKNOWN_ACTION);
        long targetId = Long.parseLong(parts[1]);
        ModeratorIdentity moderator = new ModeratorIdentity(telegramId, username);
        long promptChat = "private".equals(chatType) ? chatId : telegramId;
        BotLocale promptLocale = "private".equals(chatType) ? locale : locales.userLocale(telegramId);
        switch (parts[2]) {
            case "bot_block" -> {
                FlowSession session = new FlowSession(FlowKind.ADMIN_BOT_BLOCK_REASON);
                session.put("targetId", targetId);
                session.put("confirmationData", "admin-user:" + targetId + ":bot_block_confirm");
                session.put("reason", "");
                sessions.put(telegramId, session);
                telegram.sendMessage(promptChat, t(promptLocale, MessageKey.BOT_BLOCK_REASON),
                        keyboards.inline(new String[][]{
                                {t(promptLocale, MessageKey.BUTTON_SKIP),
                                        "admin-user:" + targetId + ":bot_block_skip"},
                                {t(promptLocale, MessageKey.BUTTON_CANCEL), "nav:admin"}}));
            }
            case "bot_block_skip" -> {
                FlowSession session = requireBotBlockSession(telegramId, targetId);
                session.kind = FlowKind.ADMIN_BOT_BLOCK_CONFIRM;
                showBotBlockConfirmation(promptChat, targetId,
                        "admin-user:" + targetId + ":bot_block_confirm", promptLocale);
            }
            case "bot_unblock" -> telegram.sendMessage(promptChat,
                    t(promptLocale, MessageKey.BOT_UNBLOCK_CONFIRM, Long.toString(targetId)),
                    keyboards.inline(new String[][]{
                            {t(promptLocale, MessageKey.BUTTON_CONFIRM),
                                    "admin-user:" + targetId + ":bot_unblock_confirm"},
                            {t(promptLocale, MessageKey.BUTTON_CANCEL), "nav:admin"}}));
            case "bot_block_confirm", "bot_unblock_confirm" -> {
                boolean blocked = "bot_block_confirm".equals(parts[2]);
                String reason = blocked ? requireBotBlockSession(telegramId, targetId).get("reason") : "";
                TelegramUserModerationService.ChangeResult result = userModeration.setBlocked(
                        moderator, targetId, blocked, reason);
                sessions.remove(telegramId);
                telegram.sendMessage(promptChat, t(promptLocale, blocked ? MessageKey.BOT_BLOCKED_DONE
                                : MessageKey.BOT_UNBLOCKED_DONE, Long.toString(result.targetTelegramId())),
                        keyboards.adminMenu(promptLocale));
            }
            default -> throw new LocalizedUiException(MessageKey.ERROR_UNKNOWN_ACTION);
        }
        telegram.answerCallback(callbackId, t(locale, MessageKey.CALLBACK_DONE));
    }

    private FlowSession requireDisableSession(long telegramId, long accountId) {
        FlowSession session = sessions.get(telegramId);
        if (session == null || (session.kind != FlowKind.ADMIN_DISABLE_REASON
                && session.kind != FlowKind.ADMIN_DISABLE_CONFIRM)
                || !Long.toString(accountId).equals(session.get("accountId"))) {
            throw new LocalizedUiException(MessageKey.ERROR_INVALID_STATE);
        }
        return session;
    }

    private FlowSession requireBotBlockSession(long telegramId, long targetTelegramId) {
        FlowSession session = sessions.get(telegramId);
        if (session == null || (session.kind != FlowKind.ADMIN_BOT_BLOCK_REASON
                && session.kind != FlowKind.ADMIN_BOT_BLOCK_CONFIRM)
                || !Long.toString(targetTelegramId).equals(session.get("targetId"))) {
            throw new LocalizedUiException(MessageKey.ERROR_INVALID_STATE);
        }
        return session;
    }

    private void showBotBlockConfirmation(long chatId, long targetTelegramId,
                                          String confirmationData, BotLocale locale)
            throws IOException, InterruptedException {
        telegram.sendMessage(chatId, t(locale, MessageKey.BOT_BLOCK_CONFIRM,
                Long.toString(targetTelegramId)), keyboards.inline(new String[][]{
                {t(locale, MessageKey.BUTTON_CONFIRM), confirmationData},
                {t(locale, MessageKey.BUTTON_CANCEL), "nav:admin"}}));
    }

    private void showDisableConfirmation(long chatId, AccountView account, BotLocale locale)
            throws IOException, InterruptedException {
        telegram.sendMessage(chatId, t(locale, MessageKey.ACCOUNT_DISABLE_CONFIRM, account.nickname()),
                keyboards.inline(new String[][]{
                        {t(locale, MessageKey.BUTTON_DISABLE_ACCOUNT),
                                "admin-account:" + account.id() + ":disable_confirm"},
                        {t(locale, MessageKey.BUTTON_CANCEL),
                                "admin-account:" + account.id() + ":disable_cancel"}
                }));
    }

    private void sendAccountStateResult(
            long chatId, AccountStateChangeResult result, boolean enabled, BotLocale locale)
            throws IOException, InterruptedException {
        MessageKey key = result.changed()
                ? (enabled ? MessageKey.ACCOUNT_ENABLED_DONE : MessageKey.ACCOUNT_DISABLED_DONE)
                : (enabled ? MessageKey.ACCOUNT_ALREADY_ENABLED : MessageKey.ACCOUNT_ALREADY_DISABLED);
        String text = result.changed()
                ? t(locale, key, result.account().nickname(),
                        localization.formatInstant(locale, result.occurredAt()))
                : t(locale, key, result.account().nickname());
        telegram.sendMessage(chatId, text, accountAdminKeyboard(result.account(), locale));
        if (result.changed()) {
            BotLocale ownerLocale = locales.userLocale(result.account().ownerTelegramId());
            telegram.sendMessage(result.account().ownerTelegramId(), t(ownerLocale,
                    enabled ? MessageKey.ACCOUNT_ENABLED_USER : MessageKey.ACCOUNT_DISABLED_USER,
                    result.account().nickname()), null);
        }
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
                telegram.sendHtmlMessage(result.creatorTelegramId(), passwordMessage(userLocale,
                        MessageKey.TICKET_APPROVED_USER, new String(password), result.nickname()), null);
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
        rejectTicket(chatId, messageId, ticketId, moderator, adminLocale, "");
    }

    private void rejectTicket(
            long chatId, long messageId, long ticketId, ModeratorIdentity moderator,
            BotLocale adminLocale, String reason) throws IOException, InterruptedException {
        TicketDetail detail = tickets.ticketForModerator(moderator, ticketId);
        if (detail.ticket().type() == TicketType.REGISTRATION) {
            try (TicketDecisionResult result = registrationTickets.rejectRegistration(ticketId, moderator, reason)) {
                if (result.outcome() == TicketDecisionResult.Outcome.ALREADY_DECIDED) {
                    throw new WorkflowException(WorkflowException.Code.INVALID_STATE,
                            "Registration request was already processed");
                }
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
                reason == null || reason.isBlank()
                        ? t(userLocale, MessageKey.TICKET_REJECTED_USER, Long.toString(ticketId))
                        : t(userLocale, MessageKey.TICKET_REJECTED_USER_WITH_REASON, reason), null);
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
        telegram.sendMessage(chatId, t(locale, MessageKey.REG_NICKNAME), keyboards.wizard(locale, false));
    }

    private void beginRegistrationWithSubscriptionGate(
            long chatId, long telegramId, String username, BotLocale locale)
            throws IOException, InterruptedException {
        if (accounts.hasEnabledAccount(telegramId) || tickets.pendingRegistration(telegramId).isPresent()
                || !features.subscriptionCheckEnabled()) {
            beginRegistration(chatId, telegramId, username, locale);
            return;
        }
        if (!features.subscriptionCheckAvailable()) {
            showSubscriptionGate(chatId, locale, MessageKey.SUBSCRIPTION_UNAVAILABLE, false);
            return;
        }
        try {
            if (telegram.getChatMembership(features.subscriptionChannel(), telegramId)
                    == TelegramMembership.SUBSCRIBED) {
                beginRegistration(chatId, telegramId, username, locale);
            } else {
                showSubscriptionGate(chatId, locale, MessageKey.SUBSCRIPTION_REQUIRED, true);
            }
        } catch (IOException exception) {
            showSubscriptionGate(chatId, locale, MessageKey.SUBSCRIPTION_UNAVAILABLE, false);
        }
    }

    private void showSubscriptionGate(long chatId, BotLocale locale, MessageKey message, boolean allowSubscribe)
            throws IOException, InterruptedException {
        List<String[]> buttons = new ArrayList<>();
        if (allowSubscribe && features.subscriptionChannelUrl() != null) {
            buttons.add(new String[]{t(locale, MessageKey.BUTTON_SUBSCRIBE), features.subscriptionChannelUrl()});
        }
        buttons.add(new String[]{t(locale, MessageKey.BUTTON_CHECK_SUBSCRIPTION),
                "registration:subscription:retry"});
        buttons.add(new String[]{t(locale, MessageKey.BUTTON_MAIN_MENU), "nav:main"});
        telegram.sendMessage(chatId, t(locale, message), keyboards.inline(buttons.toArray(String[][]::new)));
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
                formatTicket(detail, adminLocale),
                adminTicketKeyboard(detail.ticket(), null, true, adminLocale));
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
                    ? FlowKind.PROBLEM_DESCRIPTION : FlowKind.VIOLATION_OFFENDER_KNOWN;
            telegram.sendMessage(chatId,
                    t(locale, kind == FlowKind.PROBLEM_ACCOUNT
                            ? MessageKey.PROBLEM_DESCRIPTION : MessageKey.VIOLATION_OFFENDER_KNOWN),
                    kind == FlowKind.PROBLEM_ACCOUNT
                            ? keyboards.wizard(locale, false) : keyboards.offenderKnown(locale));
        } else {
            MessageKey key = kind == FlowKind.PROBLEM_ACCOUNT
                    ? MessageKey.PROBLEM_SELECT_ACCOUNT : MessageKey.VIOLATION_SELECT_ACCOUNT;
            telegram.sendMessage(chatId, t(locale, key, formatAccounts(owned, locale)),
                    keyboards.wizard(locale, false));
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
                telegram.sendHtmlMessage(ownerId, passwordMessage(ownerLocale,
                        MessageKey.CREDENTIAL_CREATED, new String(password), result.nickname()), null);
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

    private void showRelevantMenu(long chatId, long telegramId, MessageKey message, Object... arguments)
            throws IOException, InterruptedException {
        BotLocale locale = locales.userLocale(telegramId);
        if (moderators.isEnabled(telegramId)) {
            telegram.sendMessage(chatId, t(locale, message, arguments), keyboards.adminMenu(locale));
        } else {
            showMainMenu(chatId, telegramId, message, arguments);
        }
    }

    private boolean isAdministrativeContext(long telegramId) {
        if (pendingModeratorActions.find(telegramId).isPresent()) return true;
        FlowSession session = sessions.get(telegramId);
        return session != null && switch (session.kind) {
            case MANUAL_OWNER, MANUAL_NICKNAME, MANUAL_LICENSE, MANUAL_CONFIRM,
                    ADMIN_SEARCH, ADMIN_DISABLE_REASON, ADMIN_DISABLE_CONFIRM,
                    ADMIN_BOT_BLOCK_REASON, ADMIN_BOT_BLOCK_CONFIRM, RESYNC_ACCOUNT -> true;
            default -> false;
        };
    }

    private void completeModeratorPending(
            long chatId, long telegramId, String username, String text,
            ModeratorPendingAction pending, BotLocale locale) throws IOException, InterruptedException {
        requireModerator(telegramId);
        ModeratorIdentity moderator = new ModeratorIdentity(telegramId, username);
        if (pending.type() == ModeratorPendingAction.Type.REGISTRATION_REJECTION_REASON) {
            if (text.isBlank()) throw new LocalizedUiException(MessageKey.TICKET_COMMENT_REQUIRED);
            rejectTicket(chatId, 0, pending.ticketId(), moderator, locale, text);
            pendingModeratorActions.clear(telegramId);
            return;
        }
        if (pending.type() == ModeratorPendingAction.Type.TICKET_CLOSURE_COMMENT) {
            if (text.isBlank()) throw new LocalizedUiException(MessageKey.TICKET_COMMENT_REQUIRED);
            finishTicketClosure(chatId, 0, pending.ticketId(), moderator,
                    pending.targetStatus(), text, locale);
            pendingModeratorActions.clear(telegramId);
            return;
        }
        if (pending.type() != ModeratorPendingAction.Type.REPLY_TO_TICKET) {
            pendingModeratorActions.clear(telegramId);
            throw new LocalizedUiException(MessageKey.ERROR_UNKNOWN_ACTION);
        }
        tickets.moderatorReply(pending.ticketId(), moderator, text);
        pendingModeratorActions.clear(telegramId);
        TicketDetail detail = tickets.ticketForModerator(moderator, pending.ticketId());
        BotLocale userLocale = locales.userLocale(detail.ticket().creatorTelegramId());
        telegram.sendMessage(detail.ticket().creatorTelegramId(),
                t(userLocale, MessageKey.TICKET_REPLY_FOR_USER,
                        Long.toString(pending.ticketId()), text),
                keyboards.inline(new String[][]{{t(userLocale, MessageKey.BUTTON_REPLY),
                        "ticket:" + pending.ticketId() + ":user_reply"}}));
        telegram.sendMessage(chatId,
                t(locale, MessageKey.TICKET_REPLY_SENT, Long.toString(pending.ticketId())),
                keyboards.inline(new String[][]{
                        {t(locale, MessageKey.BUTTON_BACK), "ticket:" + pending.ticketId() + ":refresh"},
                        {t(locale, MessageKey.BUTTON_ADMIN_MENU), "nav:admin"}
                }));
    }

    private void handleBack(long chatId, long telegramId, BotLocale locale)
            throws IOException, InterruptedException {
        FlowSession session = sessions.get(telegramId);
        if (session == null) {
            showRelevantMenu(chatId, telegramId, MessageKey.MAIN_CHOOSE_ACTION);
            return;
        }
        switch (session.kind) {
            case REG_LICENSE -> { session.kind = FlowKind.REG_NICKNAME;
                telegram.sendMessage(chatId, t(locale, MessageKey.REG_NICKNAME), keyboards.wizard(locale, false)); }
            case REG_AGE -> { session.kind = FlowKind.REG_LICENSE; yesNo(chatId, locale, MessageKey.REG_LICENSE); }
            case REG_EXPERIENCE -> { session.kind = FlowKind.REG_AGE;
                telegram.sendMessage(chatId, t(locale, MessageKey.REG_AGE), keyboards.wizard(locale, false)); }
            case REG_CONFIRM -> { session.kind = FlowKind.REG_EXPERIENCE;
                telegram.sendMessage(chatId, t(locale, MessageKey.REG_EXPERIENCE), keyboards.wizard(locale, false)); }
            case PROBLEM_DESCRIPTION -> { session.kind = FlowKind.PROBLEM_ACCOUNT;
                telegram.sendMessage(chatId, t(locale, MessageKey.PROBLEM_SELECT_ACCOUNT,
                        formatAccounts(requireRegistered(telegramId), locale)), keyboards.wizard(locale, false)); }
            case PROBLEM_ATTACHMENTS -> { session.kind = FlowKind.PROBLEM_DESCRIPTION;
                telegram.sendMessage(chatId, t(locale, MessageKey.PROBLEM_DESCRIPTION), keyboards.wizard(locale, false)); }
            case VIOLATION_OFFENDER_KNOWN -> { session.kind = FlowKind.VIOLATION_ACCOUNT;
                telegram.sendMessage(chatId, t(locale, MessageKey.VIOLATION_SELECT_ACCOUNT,
                        formatAccounts(requireRegistered(telegramId), locale)), keyboards.wizard(locale, false)); }
            case VIOLATION_OFFENDER -> { session.kind = FlowKind.VIOLATION_OFFENDER_KNOWN;
                telegram.sendMessage(chatId, t(locale, MessageKey.VIOLATION_OFFENDER_KNOWN), keyboards.offenderKnown(locale)); }
            case VIOLATION_DESCRIPTION -> { session.kind = FlowKind.VIOLATION_OFFENDER_KNOWN;
                telegram.sendMessage(chatId, t(locale, MessageKey.VIOLATION_OFFENDER_KNOWN), keyboards.offenderKnown(locale)); }
            case VIOLATION_ATTACHMENTS -> { session.kind = FlowKind.VIOLATION_DESCRIPTION;
                telegram.sendMessage(chatId, t(locale, MessageKey.VIOLATION_DESCRIPTION), keyboards.wizard(locale, false)); }
            case ADD_LICENSE -> { session.kind = FlowKind.ADD_NICKNAME;
                telegram.sendMessage(chatId, t(locale, MessageKey.ADD_NICKNAME), keyboards.wizard(locale, false)); }
            case ADD_CONFIRM -> { session.kind = FlowKind.ADD_LICENSE; yesNo(chatId, locale, MessageKey.ADD_LICENSE); }
            case MANUAL_NICKNAME -> { session.kind = FlowKind.MANUAL_OWNER;
                telegram.sendMessage(chatId, t(locale, MessageKey.MANUAL_OWNER), keyboards.wizard(locale, true)); }
            case MANUAL_LICENSE -> { session.kind = FlowKind.MANUAL_NICKNAME;
                telegram.sendMessage(chatId, t(locale, MessageKey.MANUAL_NICKNAME), keyboards.wizard(locale, true)); }
            case MANUAL_CONFIRM -> { session.kind = FlowKind.MANUAL_LICENSE; yesNoAdmin(chatId, locale, MessageKey.MANUAL_LICENSE); }
            case SERVER_INFORMATION -> {
                sessions.remove(telegramId);
                showMainMenu(chatId, telegramId, MessageKey.MAIN_CHOOSE_ACTION);
            }
            default -> {
                sessions.remove(telegramId);
                showRelevantMenu(chatId, telegramId, MessageKey.MAIN_CHOOSE_ACTION);
            }
        }
    }

    private void handleServerInformationCallback(long chatId, long telegramId, String data, BotLocale locale)
            throws IOException, InterruptedException {
        requireRegistered(telegramId);
        switch (data) {
            case "server_info:how_to_play" -> showHowToPlay(chatId, telegramId, locale);
            case "server_info:modpack" -> {
                if (features.modpackDownloadUrl() == null) throw new LocalizedUiException(MessageKey.ERROR_UNKNOWN_ACTION);
                showModpackDownload(chatId, locale);
            }
            case "server_info:map" -> {
                if (features.serverMapUrl() == null) throw new LocalizedUiException(MessageKey.ERROR_UNKNOWN_ACTION);
                showServerMap(chatId, locale);
            }
            case "server_info:status" -> {
                if (!features.serverStatusEnabled()) throw new LocalizedUiException(MessageKey.ERROR_UNKNOWN_ACTION);
                showServerStatus(chatId, locale);
            }
            case "server_info:back" -> {
                sessions.remove(telegramId);
                pendingModeratorActions.clear(telegramId);
                showMainMenu(chatId, telegramId, MessageKey.MAIN_CHOOSE_ACTION);
            }
            default -> throw new LocalizedUiException(MessageKey.ERROR_UNKNOWN_ACTION);
        }
    }

    private void showHowToPlay(long chatId, long telegramId, BotLocale locale)
            throws IOException, InterruptedException {
        sessions.remove(telegramId);
        List<String[]> buttons = new ArrayList<>();
        if (howToPlayUrl != null && howToPlayUrl.startsWith("https://")) {
            buttons.add(new String[]{t(locale, MessageKey.HOW_TO_PLAY_GUIDE), howToPlayUrl});
        }
        buttons.add(new String[]{t(locale, MessageKey.BUTTON_MAIN_MENU), "nav:main"});
        telegram.sendMessage(chatId, features.howToPlay(locale, t(locale, MessageKey.HOW_TO_PLAY)),
                keyboards.inline(buttons.toArray(String[][]::new)));
    }

    private void showServerMap(long chatId, BotLocale locale) throws IOException, InterruptedException {
        telegram.sendMessage(chatId, t(locale, MessageKey.SERVER_MAP_OPEN),
                keyboards.inline(new String[][]{
                        {t(locale, MessageKey.MENU_SERVER_MAP), features.serverMapUrl()},
                        {t(locale, MessageKey.BUTTON_MAIN_MENU), "nav:main"}}));
    }

    private void showModpackDownload(long chatId, BotLocale locale) throws IOException, InterruptedException {
        telegram.sendMessage(chatId, t(locale, MessageKey.MODPACK_DOWNLOAD_OPEN),
                keyboards.inline(new String[][]{
                        {t(locale, MessageKey.MENU_MODPACK_DOWNLOAD), features.modpackDownloadUrl()},
                        {t(locale, MessageKey.BUTTON_MAIN_MENU), "nav:main"}}));
    }

    private void showAccount(long chatId, long telegramId, BotLocale locale)
            throws IOException, InterruptedException {
        List<AccountView> owned = requireRegistered(telegramId);
        String username = accounts.telegramUsername(telegramId);
        String identity = username == null || username.isBlank()
                ? t(locale, MessageKey.TICKET_NO_USERNAME, Long.toString(telegramId))
                : t(locale, MessageKey.TICKET_IDENTITY, Long.toString(telegramId), username);
        telegram.sendMessage(chatId,
                t(locale, MessageKey.ACCOUNT_LIST, identity, formatAccounts(owned, locale)),
                keyboards.accountMenu(locale));
    }

    private void showPasswordChoices(long chatId, long telegramId, BotLocale locale)
            throws IOException, InterruptedException {
        List<AccountView> owned = requireRegistered(telegramId);
        List<String[]> buttons = new ArrayList<>(List.of(owned.stream()
                .map(account -> new String[]{account.nickname(), "account:" + account.id() + ":password"})
                .toArray(String[][]::new)));
        buttons.add(new String[]{t(locale, MessageKey.BUTTON_BACK), "nav:main"});
        buttons.add(new String[]{t(locale, MessageKey.BUTTON_MAIN_MENU), "nav:main"});
        telegram.sendMessage(chatId, t(locale, MessageKey.ACCOUNT_SELECT_PASSWORD),
                keyboards.inline(buttons.toArray(String[][]::new)));
    }

    private void showPlaytimeChoices(long chatId, long telegramId, BotLocale locale)
            throws IOException, InterruptedException {
        List<AccountView> owned = requireRegistered(telegramId);
        if (owned.size() == 1) {
            showPlaytime(chatId, telegramId, owned.getFirst().id(), locale);
            return;
        }
        List<String[]> buttons = new ArrayList<>();
        for (AccountView account : owned) {
            buttons.add(new String[]{account.nickname(), "account:" + account.id() + ":playtime"});
        }
        buttons.add(new String[]{t(locale, MessageKey.BUTTON_BACK), "nav:main"});
        telegram.sendMessage(chatId, t(locale, MessageKey.PLAYTIME_SELECT),
                keyboards.inline(buttons.toArray(String[][]::new)));
    }

    private void showPlaytime(long chatId, long telegramId, long accountId, BotLocale locale)
            throws IOException, InterruptedException {
        if (telemetry == null) throw new LocalizedUiException(MessageKey.ERROR_INVALID_STATE);
        ServerTelemetryService.Playtime value = telemetry.playtime(telegramId, accountId);
        telegram.sendMessage(chatId, t(locale, MessageKey.PLAYTIME_SUMMARY, value.nickname(),
                LocalizedDurationFormatter.format(value.last24Hours(), locale),
                LocalizedDurationFormatter.format(value.last7Days(), locale),
                LocalizedDurationFormatter.format(value.allTime(), locale)), keyboards.inline(new String[][]{
                {t(locale, MessageKey.BUTTON_BACK), "nav:main"}}));
    }

    private void showServerStatus(long chatId, BotLocale locale) throws IOException, InterruptedException {
        if (telemetry == null) throw new LocalizedUiException(MessageKey.ERROR_INVALID_STATE);
        ServerTelemetryService.ServerStatus status = telemetry.status(serverId);
        String text;
        if (status.online()) {
            text = t(locale, MessageKey.SERVER_STATUS_ONLINE,
                    Integer.toString(status.onlinePlayers()), Integer.toString(status.maxPlayers()));
            if (!features.showPlayerCount()) text = text.lines().findFirst().orElse(text);
        } else {
            String updated = status.lastHeartbeatAt() == null ? "—"
                    : localization.formatInstant(locale, status.lastHeartbeatAt());
            text = t(locale, MessageKey.SERVER_STATUS_OFFLINE, updated);
        }
        List<String[]> buttons = new ArrayList<>();
        if (status.online() && features.showPlayerList()) {
            buttons.add(new String[]{t(locale, MessageKey.BUTTON_PLAYER_LIST), "server:players"});
        }
        buttons.add(new String[]{t(locale, MessageKey.BUTTON_BACK), "nav:main"});
        telegram.sendMessage(chatId, text, keyboards.inline(buttons.toArray(String[][]::new)));
    }

    private void showPlayerList(long chatId, BotLocale locale) throws IOException, InterruptedException {
        if (telemetry == null) throw new LocalizedUiException(MessageKey.ERROR_INVALID_STATE);
        ServerTelemetryService.ServerStatus status = telemetry.status(serverId);
        if (!status.online()) { showServerStatus(chatId, locale); return; }
        List<ServerTelemetryService.OnlinePlayer> players = telemetry.onlinePlayers(serverId);
        MessageKey heading = players.isEmpty() && status.onlinePlayers() > 0
                ? MessageKey.PLAYER_LIST_UNAVAILABLE
                : players.isEmpty() ? MessageKey.PLAYER_LIST_EMPTY : MessageKey.PLAYER_LIST_TITLE;
        StringBuilder text = new StringBuilder(t(locale, heading));
        if (!players.isEmpty()) for (ServerTelemetryService.OnlinePlayer player : players) {
            String duration = features.showSessionDuration()
                    ? LocalizedDurationFormatter.format(player.sessionDuration(), locale) : "";
            text.append('\n').append(t(locale, MessageKey.PLAYER_LIST_ROW,
                    player.nickname(), duration).stripTrailing());
        }
        telegram.sendMessage(chatId, text.toString(), keyboards.inline(new String[][]{
                {t(locale, MessageKey.BUTTON_BACK), "nav:main"}}));
    }

    private void showPlayerStatistics(long chatId, ModeratorIdentity moderator, int page, BotLocale locale)
            throws IOException, InterruptedException {
        if (telemetry == null) throw new LocalizedUiException(MessageKey.ERROR_INVALID_STATE);
        List<ServerTelemetryService.PlayerStatistic> values = telemetry.playerStatistics(
                moderator, moderators, page, 10);
        StringBuilder text = new StringBuilder(t(locale, MessageKey.PLAYER_STATISTICS_TITLE,
                Integer.toString(page + 1)));
        for (ServerTelemetryService.PlayerStatistic value : values) {
            String seen;
            if (value.online()) seen = t(locale, MessageKey.PLAYER_ONLINE);
            else if (value.lastSeen() == null) seen = "—";
            else seen = localization.formatInstant(locale, value.lastSeen()) + " ("
                    + LocalizedDurationFormatter.format(java.time.Duration.between(
                    value.lastSeen(), telemetry.currentTime()), locale)
                    + (locale == BotLocale.RUSSIAN ? " назад)" : " ago)");
            text.append("\n\n").append(t(locale, MessageKey.PLAYER_STATISTICS_ROW,
                    value.nickname(), LocalizedDurationFormatter.format(value.allTime(), locale), seen));
        }
        List<String[]> buttons = new ArrayList<>();
        if (page > 0) buttons.add(new String[]{t(locale, MessageKey.BUTTON_PREVIOUS), "players:stats:" + (page - 1)});
        if (values.size() == 10) buttons.add(new String[]{t(locale, MessageKey.BUTTON_NEXT), "players:stats:" + (page + 1)});
        buttons.add(new String[]{t(locale, MessageKey.BUTTON_ADMIN_MENU), "nav:admin"});
        telegram.sendMessage(chatId, text.toString(), keyboards.inline(buttons.toArray(String[][]::new)));
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
        buttons.add(new String[]{t(locale, MessageKey.BUTTON_MAIN_MENU), "nav:main"});
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
        buttons.add(new String[]{t(locale, MessageKey.BUTTON_BACK), "tickets:page:0"});
        buttons.add(new String[]{t(locale, MessageKey.BUTTON_MAIN_MENU), "nav:main"});
        telegram.sendMessage(chatId, formatTicket(detail, locale),
                keyboards.inline(buttons.toArray(String[][]::new)));
    }

    private void showAdminTickets(
            long chatId, ModeratorIdentity moderator, TicketStatus status,
            TicketType type, BotLocale locale) throws IOException, InterruptedException {
        String filter = status == TicketStatus.OPEN ? "new"
                : status == TicketStatus.IN_PROGRESS ? "progress"
                : type == TicketType.REGISTRATION ? "registrations"
                : type == TicketType.PROBLEM ? "problems"
                : type == TicketType.VIOLATION ? "violations" : "all";
        showAdminTickets(chatId, moderator, status, type, null, 0, filter, locale);
    }

    private void showAdminFilter(
            long chatId, ModeratorIdentity moderator, String filter, int page, BotLocale locale)
            throws IOException, InterruptedException {
        switch (filter) {
            case "new" -> showAdminTickets(chatId, moderator, TicketStatus.OPEN, null,
                    null, page, filter, locale);
            case "progress" -> showAdminTickets(chatId, moderator, TicketStatus.IN_PROGRESS, null,
                    null, page, filter, locale);
            case "mine" -> showAdminTickets(chatId, moderator, null, null,
                    moderator.telegramId(), page, filter, locale);
            case "registrations" -> showAdminTickets(chatId, moderator, null, TicketType.REGISTRATION,
                    null, page, filter, locale);
            case "problems" -> showAdminTickets(chatId, moderator, null, TicketType.PROBLEM,
                    null, page, filter, locale);
            case "violations" -> showAdminTickets(chatId, moderator, null, TicketType.VIOLATION,
                    null, page, filter, locale);
            default -> throw new LocalizedUiException(MessageKey.ERROR_UNKNOWN_ACTION);
        }
    }

    private void showAdminTickets(
            long chatId, ModeratorIdentity moderator, TicketStatus status, TicketType type,
            Long assignedModeratorId, int page, String filter, BotLocale locale)
            throws IOException, InterruptedException {
        if (page < 0) throw new LocalizedUiException(MessageKey.ERROR_INVALID_INPUT);
        List<TicketListItem> items = tickets.adminTickets(
                moderator, status, type, assignedModeratorId, page);
        if (items.isEmpty()) {
            List<String[]> navigation = new ArrayList<>();
            if (page > 0) navigation.add(new String[]{t(locale, MessageKey.BUTTON_PREVIOUS),
                    "admin:list:" + filter + ":" + (page - 1)});
            navigation.add(new String[]{t(locale, MessageKey.BUTTON_ADMIN_MENU), "nav:admin"});
            telegram.sendMessage(chatId, t(locale, MessageKey.ADMIN_NO_TICKETS),
                    keyboards.inline(navigation.toArray(String[][]::new)));
            return;
        }
        for (TicketListItem item : items) showAdminTicket(chatId, moderator, item.id(), locale);
        List<String[]> navigation = new ArrayList<>();
        if (page > 0) navigation.add(new String[]{t(locale, MessageKey.BUTTON_PREVIOUS),
                "admin:list:" + filter + ":" + (page - 1)});
        if (items.size() == SupportTicketService.PAGE_SIZE) {
            navigation.add(new String[]{t(locale, MessageKey.BUTTON_NEXT),
                    "admin:list:" + filter + ":" + (page + 1)});
        }
        navigation.add(new String[]{t(locale, MessageKey.BUTTON_ADMIN_MENU), "nav:admin"});
        telegram.sendMessage(chatId, t(locale, MessageKey.ADMIN_PAGE,
                        Integer.toString(page + 1)),
                keyboards.inline(navigation.toArray(String[][]::new)));
    }

    private void showAdminTicket(
            long chatId, ModeratorIdentity moderator, long ticketId, BotLocale locale)
            throws IOException, InterruptedException {
        TicketDetail detail = tickets.ticketForModerator(moderator, ticketId);
        boolean approvable = detail.ticket().type() == TicketType.REGISTRATION
                || detail.ticket().type() == TicketType.ADD_MINECRAFT_ACCOUNT;
        telegram.sendMessage(chatId, formatTicket(detail, locale),
                adminTicketKeyboard(detail.ticket(), moderator.telegramId(), approvable, locale));
    }

    private void showSearch(
            long chatId, ModeratorIdentity moderator, String query, BotLocale locale)
            throws IOException, InterruptedException {
        List<TicketListItem> results = tickets.search(moderator, query);
        List<AccountView> accountResults = accounts.search(moderator, query);
        if (results.isEmpty() && accountResults.isEmpty()) {
            telegram.sendMessage(chatId, t(locale, MessageKey.ADMIN_NOTHING_FOUND),
                    keyboards.adminMenu(locale));
            return;
        }
        for (TicketListItem item : results) showAdminTicket(chatId, moderator, item.id(), locale);
        for (AccountView account : accountResults) showAdminAccount(chatId, moderator, account.id(), locale);
    }

    private void showAdminAccount(
            long chatId, ModeratorIdentity moderator, long accountId, BotLocale locale)
            throws IOException, InterruptedException {
        AccountView account = accounts.accountForModerator(moderator, accountId);
        String identity = formatIdentity(account.ownerTelegramId(),
                accounts.telegramUsername(account.ownerTelegramId()), locale);
        telegram.sendMessage(chatId, t(locale, MessageKey.ACCOUNT_ADMIN_DETAILS,
                account.nickname(), identity,
                t(locale, account.enabled() ? MessageKey.ACCOUNT_ENABLED : MessageKey.ACCOUNT_DISABLED),
                Integer.toString(account.credentialVersion()),
                localization.formatInstant(locale, account.createdAt())),
                accountAdminKeyboard(account, locale));
    }

    private ObjectNode accountAdminKeyboard(AccountView account, BotLocale locale) {
        List<String[]> buttons = new ArrayList<>();
        buttons.add(new String[]{t(locale, account.enabled() ? MessageKey.BUTTON_DISABLE_ACCOUNT
                        : MessageKey.BUTTON_ENABLE_ACCOUNT),
                "admin-account:" + account.id() + (account.enabled() ? ":disable" : ":enable")});
        if (userModeration != null) {
            boolean blocked = userModeration.isBlocked(account.ownerTelegramId());
            buttons.add(new String[]{t(locale, blocked ? MessageKey.BUTTON_UNBLOCK_USER : MessageKey.BUTTON_BLOCK_USER),
                    "admin-account:" + account.id() + (blocked ? ":bot_unblock" : ":bot_block")});
        }
        buttons.add(new String[]{t(locale, MessageKey.BUTTON_BACK), "nav:admin"});
        buttons.add(new String[]{t(locale, MessageKey.BUTTON_MAIN_MENU), "nav:main"});
        return keyboards.inline(buttons.toArray(String[][]::new));
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
                adminTicketKeyboard(detail.ticket(), null, approvable, adminLocale));
        tickets.attachAdminProjection(ticketId, adminChatId, messageId);
        for (AttachmentReference attachment : detail.attachments()) {
            telegram.sendAttachment(adminChatId, attachment);
        }
    }

    private ObjectNode adminTicketKeyboard(
            TicketListItem ticket, Long viewerModeratorId, boolean approvable, BotLocale locale) {
        List<String[]> buttons = new ArrayList<>();
        long ticketId = ticket.id();
        if (ticket.status() == TicketStatus.OPEN) {
            buttons.add(new String[]{t(locale, MessageKey.BUTTON_TAKE), "ticket:" + ticketId + ":take"});
        } else if ((ticket.status() == TicketStatus.IN_PROGRESS
                || ticket.status() == TicketStatus.WAITING_USER)
                && Objects.equals(ticket.assignedModeratorId(), viewerModeratorId)) {
            buttons.add(new String[]{t(locale, MessageKey.BUTTON_REPLY), "ticket:" + ticketId + ":reply"});
            if (approvable) {
                buttons.add(new String[]{t(locale, MessageKey.BUTTON_APPROVE),
                        "ticket:" + ticketId + ":approve"});
            } else if (ticket.status() == TicketStatus.IN_PROGRESS) {
                buttons.add(new String[]{t(locale, MessageKey.BUTTON_RESOLVE),
                        "ticket:" + ticketId + ":resolve"});
            }
            buttons.add(new String[]{t(locale, MessageKey.BUTTON_REJECT),
                    "ticket:" + ticketId + ":reject"});
        } else if ((ticket.status() == TicketStatus.RESOLVED || ticket.status() == TicketStatus.REJECTED)
                && Objects.equals(ticket.assignedModeratorId(), viewerModeratorId)) {
            buttons.add(new String[]{t(locale, MessageKey.BUTTON_CLOSE),
                    "ticket:" + ticketId + ":close"});
        }
        if (userModeration != null) {
            boolean blocked = userModeration.isBlocked(ticket.creatorTelegramId());
            buttons.add(new String[]{t(locale, blocked ? MessageKey.BUTTON_UNBLOCK_USER : MessageKey.BUTTON_BLOCK_USER),
                    "admin-user:" + ticket.creatorTelegramId()
                            + (blocked ? ":bot_unblock" : ":bot_block")});
        }
        buttons.add(new String[]{t(locale, MessageKey.BUTTON_REFRESH),
                "ticket:" + ticketId + ":refresh"});
        buttons.add(new String[]{t(locale, MessageKey.BUTTON_ADMIN_MENU), "nav:admin"});
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
            telegram.sendHtmlMessage(chatId, passwordMessage(locale, MessageKey.PASSWORD_CHANGED,
                    new String(password), result.nickname(),
                    Integer.toString(result.credentialVersion())), null);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private String passwordMessage(BotLocale locale, MessageKey key, String password, Object... otherArguments) {
        Object[] arguments = new Object[otherArguments.length + 1];
        if (key == MessageKey.PASSWORD_CHANGED) {
            arguments[0] = otherArguments[0];
            arguments[1] = TelegramPasswordFormatter.marker();
            arguments[2] = otherArguments[1];
        } else {
            arguments[0] = otherArguments[0];
            arguments[1] = TelegramPasswordFormatter.marker();
        }
        return TelegramPasswordFormatter.format(t(locale, key, arguments), password);
    }

    private void yesNo(long chatId, BotLocale locale, MessageKey prompt, Object... arguments)
            throws IOException, InterruptedException {
        telegram.sendMessage(chatId, t(locale, prompt, arguments), keyboards.yesNo(locale));
    }

    private void yesNoAdmin(long chatId, BotLocale locale, MessageKey prompt, Object... arguments)
            throws IOException, InterruptedException {
        telegram.sendMessage(chatId, t(locale, prompt, arguments), keyboards.yesNo(locale, true));
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
                ticketStatus(locale, ticket.status()), formatIdentity(ticket, locale),
                localization.formatInstant(locale, ticket.createdAt())));
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
        if ("offender_nickname".equals(field) && value.isBlank()) {
            return t(locale, MessageKey.VIOLATION_UNKNOWN);
        }
        return value;
    }

    private String formatIdentity(TicketListItem ticket, BotLocale locale) {
        return formatIdentity(ticket.creatorTelegramId(), ticket.creatorUsername(), locale);
    }

    private String formatIdentity(long telegramId, String username, BotLocale locale) {
        return username == null || username.isBlank()
                ? t(locale, MessageKey.TICKET_NO_USERNAME, Long.toString(telegramId))
                : t(locale, MessageKey.TICKET_IDENTITY, Long.toString(telegramId), username);
    }

    private String ticketMinecraft(TicketDetail detail) {
        String field = detail.fields().get("minecraft_nickname");
        if (field != null && !field.isBlank()) return field;
        Long affected = detail.ticket().affectedAccountId();
        if (affected == null) return "—";
        return accounts.accountsFor(detail.ticket().creatorTelegramId()).stream()
                .filter(account -> account.id() == affected)
                .map(AccountView::nickname)
                .findFirst().orElse("—");
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

    private enum TagSyncOutcome {
        UPDATED, CLEARED, UNCHANGED, SKIPPED_ADMINISTRATOR, NOT_MEMBER, FAILED
    }

    private record TagSyncStatistics(
            int processed, int updated, int cleared, int skippedAdministrators, int notMembers, int failed) { }

    private String command(String text) {
        if (text == null || !text.startsWith("/")) return "";
        String first = text.split("\\s+", 2)[0];
        int mention = first.indexOf('@');
        if (mention >= 0 && !first.substring(mention + 1).equalsIgnoreCase(botUsername)) return "";
        return (mention < 0 ? first : first.substring(0, mention)).toLowerCase(Locale.ROOT);
    }

    private enum FlowKind {
        REG_NICKNAME, REG_LICENSE, REG_AGE, REG_EXPERIENCE, REG_CONFIRM,
        PROBLEM_ACCOUNT, PROBLEM_DESCRIPTION, PROBLEM_ATTACHMENTS,
        VIOLATION_ACCOUNT, VIOLATION_OFFENDER_KNOWN, VIOLATION_OFFENDER,
        VIOLATION_DESCRIPTION, VIOLATION_ATTACHMENTS,
        ADD_NICKNAME, ADD_LICENSE, ADD_CONFIRM,
        MANUAL_OWNER, MANUAL_NICKNAME, MANUAL_LICENSE, MANUAL_CONFIRM,
        USER_REPLY, ADMIN_SEARCH, ADMIN_DISABLE_REASON, ADMIN_DISABLE_CONFIRM,
        ADMIN_BOT_BLOCK_REASON, ADMIN_BOT_BLOCK_CONFIRM, RESYNC_ACCOUNT, SERVER_INFORMATION
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
