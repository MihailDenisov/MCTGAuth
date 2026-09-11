package dev.example.mcauth.api;

public enum RemoteCommandStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    EXPIRED,
    CANCELLED;

    public boolean terminal() {
        return this == SUCCESS || this == FAILED || this == EXPIRED || this == CANCELLED;
    }
}
