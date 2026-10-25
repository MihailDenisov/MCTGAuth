package dev.example.mcauth.protocol;

public enum PlayerAuthState {
    WAITING_FOR_CLIENT,
    CHALLENGE_SENT,
    VERIFYING,
    DEVICE_PENDING,
    AUTHENTICATED,
    FAILED,
    TIMED_OUT
}
