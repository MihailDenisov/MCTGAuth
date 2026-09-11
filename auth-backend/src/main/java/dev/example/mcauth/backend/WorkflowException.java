package dev.example.mcauth.backend;

public final class WorkflowException extends RuntimeException {
    public enum Code {
        INVALID_INPUT,
        UNAUTHORIZED,
        NOT_FOUND,
        INVALID_STATE,
        CONFLICT,
        RATE_LIMITED
    }

    private final Code code;

    public WorkflowException(Code code, String safeMessage) {
        super(safeMessage);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
