package com.orderaudit.service;

/** The rules a new password must pass. Kept separate so it is trivial to unit-test. */
final class PasswordPolicy {

    static final int MIN_LENGTH = 8;

    private PasswordPolicy() {
    }

    /** Throws IllegalArgumentException with a message the user can read. */
    static void check(String password, String username) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new IllegalArgumentException("Password must be at least " + MIN_LENGTH + " characters");
        }
        if (password.length() > 72) {
            throw new IllegalArgumentException("Password must be at most 72 characters");   // bcrypt ignores the rest
        }
        if (password.isBlank()) {
            throw new IllegalArgumentException("Password must not be blank");
        }
        if (username != null && password.equalsIgnoreCase(username)) {
            throw new IllegalArgumentException("Password must not be the same as the username");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new IllegalArgumentException("Password must contain at least one letter and one digit");
        }
    }
}
