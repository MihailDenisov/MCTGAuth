package dev.example.mcauth.backend;

import java.util.Arrays;

public final class CredentialIssueResult implements AutoCloseable {
    private final long accountId;
    private final String nickname;
    private final int credentialVersion;
    private final char[] password;

    CredentialIssueResult(long accountId, String nickname, int credentialVersion, char[] password) {
        this.accountId = accountId;
        this.nickname = nickname;
        this.credentialVersion = credentialVersion;
        this.password = password;
    }

    public long accountId() { return accountId; }
    public String nickname() { return nickname; }
    public int credentialVersion() { return credentialVersion; }
    public char[] copyPassword() { return Arrays.copyOf(password, password.length); }

    @Override
    public void close() {
        Arrays.fill(password, '\0');
    }
}
