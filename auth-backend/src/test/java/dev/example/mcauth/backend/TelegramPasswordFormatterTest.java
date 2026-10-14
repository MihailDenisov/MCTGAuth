package dev.example.mcauth.backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TelegramPasswordFormatterTest {
    @Test
    void onlyEscapedPasswordIsMonospace() {
        String message = "Password: " + TelegramPasswordFormatter.marker() + "\nKeep <private> & safe.";
        assertEquals("Password: <code>A&amp;B&lt;C&gt;</code>\nKeep &lt;private&gt; &amp; safe.",
                TelegramPasswordFormatter.format(message, "A&B<C>"));
    }
}
