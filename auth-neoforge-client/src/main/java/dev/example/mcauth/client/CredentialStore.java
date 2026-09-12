package dev.example.mcauth.client;

import java.util.Arrays;

public final class CredentialStore implements AutoCloseable {
    private char[] password;
    private long generation = -1;

    public synchronized void replace(long connectionGeneration, char[] value) {
        clear();
        password = value == null ? null : value.clone();
        generation = connectionGeneration;
    }

    public synchronized char[] take(long connectionGeneration) {
        if (generation != connectionGeneration) return null;
        char[] value = password;
        password = null;
        generation = -1;
        return value;
    }

    public synchronized void clear() {
        if (password != null) Arrays.fill(password, '\0');
        password = null;
        generation = -1;
    }

    @Override
    public void close() {
        clear();
    }
}
