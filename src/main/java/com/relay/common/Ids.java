package com.relay.common;

import java.security.SecureRandom;

/**
 * Short, url-safe, prefixed identifiers (e.g. {@code run_a1b2c3...}, {@code apr_...}).
 */
public final class Ids {

    private static final char[] ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String newId(String prefix) {
        StringBuilder sb = new StringBuilder(prefix).append('_');
        for (int i = 0; i < 20; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    public static String runId() {
        return newId("run");
    }

    public static String approvalId() {
        return newId("apr");
    }
}
