package dev.example.mcauth.backend;

/** Escapes an otherwise plain Telegram message while marking only the password as HTML code. */
final class TelegramPasswordFormatter {
    private static final String MARKER = "MCTGAUTH_PASSWORD_MARKER_6F9B";

    private TelegramPasswordFormatter() { }

    static String format(String localizedTemplate, String password) {
        if (localizedTemplate == null || password == null || !localizedTemplate.contains(MARKER)) {
            throw new IllegalArgumentException("Password message marker is missing");
        }
        return escapeHtml(localizedTemplate).replace(MARKER, "<code>" + escapeHtml(password) + "</code>");
    }

    static String marker() { return MARKER; }

    static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
