package dev.example.mcauth.client;

public enum ClientAuthState {
    DISCONNECTED,
    PASSIVE,
    NEGOTIATING,
    WAITING_FOR_PASSWORD,
    COMPUTING,
    WAITING_FOR_RESULT,
    AUTHENTICATED,
    FAILED,
    TERMINAL_FAILURE
}
