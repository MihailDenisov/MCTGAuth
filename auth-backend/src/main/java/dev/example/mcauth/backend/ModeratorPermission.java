package dev.example.mcauth.backend;

public enum ModeratorPermission {
    TICKET_VIEW("ticket.view"),
    TICKET_ASSIGN("ticket.assign"),
    TICKET_REPLY("ticket.reply"),
    TICKET_RESOLVE("ticket.resolve"),
    TICKET_REJECT("ticket.reject"),
    ACCOUNT_CREATE_MANUAL("account.create.manual"),
    ACCOUNT_DISABLE("account.disable"),
    ACCOUNT_ENABLE("account.enable"),
    AUTH_SYNC_FORCE("auth.sync.force"),
    AUTH_PLAYER_RESYNC("auth.player.resync"),
    SERVER_STATUS("server.status");

    private final String key;

    ModeratorPermission(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
