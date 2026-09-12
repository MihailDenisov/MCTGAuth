package dev.example.mcauth.server;

import java.util.Optional;
import java.util.function.Function;

public final class ServerSecretProvider {
    private static final String TOKEN_ENV = "MCTGAUTH_API_TOKEN";

    private ServerSecretProvider() {
    }

    public static Optional<char[]> apiToken(String configuredToken) {
        return apiToken(configuredToken, System::getenv);
    }

    static Optional<char[]> apiToken(String configuredToken, Function<String, String> environment) {
        String override = environment.apply(TOKEN_ENV);
        String token = override == null || override.isBlank() ? configuredToken : override;
        if (token == null || token.isBlank()) return Optional.empty();
        if (token.length() < 32 || token.length() > 512 || token.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalStateException("Backend API token must contain 32-512 non-whitespace characters");
        }
        return Optional.of(token.toCharArray());
    }
}
