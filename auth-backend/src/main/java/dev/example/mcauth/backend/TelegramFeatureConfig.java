package dev.example.mcauth.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.example.mcauth.backend.telegram.i18n.BotLocale;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/** Non-secret, startup-loaded Telegram presentation and registration feature settings. */
public record TelegramFeatureConfig(
        String howToPlayEn,
        String howToPlayRu,
        boolean subscriptionCheckEnabled,
        String subscriptionChannel,
        String subscriptionChannelUrl,
        String serverMapUrl,
        String modpackDownloadUrl) {

    public static TelegramFeatureConfig defaults() {
        return new TelegramFeatureConfig(null, null, false, null, null, null, null);
    }

    public static TelegramFeatureConfig load(Path path) {
        if (path == null || !Files.exists(path)) return defaults();
        try {
            Root root = new ObjectMapper(new YAMLFactory()).readValue(path.toFile(), Root.class);
            if (root == null) return defaults();
            HowToPlay how = root.howToPlay();
            Subscription subscription = root.registration() == null
                    ? null : root.registration().subscriptionCheck();
            ExternalLink map = root.serverMap();
            ExternalLink modpack = root.modpackDownload();
            boolean subscriptionEnabled = subscription != null && Boolean.TRUE.equals(subscription.enabled());
            String channel = validChannel(subscription == null ? null : subscription.channel());
            String channelUrl = validUrl(subscription == null ? null : subscription.channelUrl(),
                    "registration.subscriptionCheck.channelUrl");
            if (subscriptionEnabled && channel == null) {
                warn("registration.subscriptionCheck.channel is invalid; registration checks will be retryable but unavailable");
            }
            return new TelegramFeatureConfig(
                    text(how == null ? null : how.en()), text(how == null ? null : how.ru()),
                    subscriptionEnabled, channel, channelUrl,
                    enabledUrl(map, "serverMap"), enabledUrl(modpack, "modpackDownload"));
        } catch (Exception exception) {
            warn("cannot read " + path + " (" + exception.getClass().getSimpleName()
                    + "); built-in text and disabled optional features will be used");
            return defaults();
        }
    }

    public String howToPlay(BotLocale locale, String fallback) {
        String configured = locale == BotLocale.RUSSIAN ? howToPlayRu : howToPlayEn;
        return configured == null ? fallback : configured;
    }

    public boolean subscriptionCheckAvailable() {
        return subscriptionCheckEnabled && subscriptionChannel != null;
    }

    private static String enabledUrl(ExternalLink link, String name) {
        if (link == null || !Boolean.TRUE.equals(link.enabled())) return null;
        String value = validUrl(link.url(), name + ".url");
        if (value == null) warn(name + " is enabled but its URL is invalid; the button will be hidden");
        return value;
    }

    private static String validUrl(String value, String name) {
        if (value == null || value.isBlank()) return null;
        try {
            String normalized = value.strip();
            URI uri = URI.create(normalized);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null) throw new IllegalArgumentException();
            return normalized;
        } catch (RuntimeException exception) {
            warn(name + " must be an absolute HTTPS URL");
            return null;
        }
    }

    private static String validChannel(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        return normalized.matches("@[A-Za-z0-9_]{5,32}") || normalized.matches("-100[0-9]{5,20}")
                ? normalized : null;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static void warn(String message) {
        System.err.println("Telegram feature configuration warning: " + message);
    }

    public record Root(HowToPlay howToPlay, Registration registration,
                       ExternalLink serverMap, ExternalLink modpackDownload) { }
    public record HowToPlay(String en, String ru) { }
    public record Registration(Subscription subscriptionCheck) { }
    public record Subscription(Boolean enabled, String channel, String channelUrl) { }
    public record ExternalLink(Boolean enabled, String url) { }
}
