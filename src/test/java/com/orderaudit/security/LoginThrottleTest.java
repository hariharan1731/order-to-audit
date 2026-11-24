package com.orderaudit.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Audit finding M5: the lock-out rule on its own, with a clock the test can move. */
class LoginThrottleTest {

    /** A clock that only moves when the test says so. */
    static class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-24T08:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    MovableClock clock;
    LoginThrottle throttle;

    @BeforeEach
    void setUp() {
        clock = new MovableClock();
        throttle = new LoginThrottle();
        throttle.useClock(clock);
    }

    private void fail(String user, int times) {
        for (int i = 0; i < times; i++) {
            throttle.recordFailure(user);
        }
    }

    @Test
    void fourWrongPasswordsDoNotLockButFiveDo() {
        fail("ravi", 4);
        assertThat(throttle.isLocked("ravi")).isFalse();
        fail("ravi", 1);
        assertThat(throttle.isLocked("ravi")).isTrue();
    }

    @Test
    void theLockRunsOutAfterFifteenMinutes() {
        fail("ravi", 5);
        clock.advance(Duration.ofMinutes(14));
        assertThat(throttle.isLocked("ravi")).isTrue();
        clock.advance(Duration.ofMinutes(2));
        assertThat(throttle.isLocked("ravi")).isFalse();
    }

    @Test
    void aGoodSignInClearsTheCount() {
        fail("ravi", 4);
        throttle.recordSuccess("ravi");
        fail("ravi", 4);
        assertThat(throttle.isLocked("ravi")).isFalse();
    }

    @Test
    void oldFailuresAreForgottenAfterTheWindow() {
        fail("ravi", 4);
        clock.advance(Duration.ofMinutes(16));
        fail("ravi", 1);
        assertThat(throttle.isLocked("ravi")).isFalse();
        fail("ravi", 3);
        assertThat(throttle.isLocked("ravi")).isFalse();     // only 4 in the new window
    }

    @Test
    void tryingWhileLockedDoesNotExtendTheLock() {
        fail("ravi", 5);
        clock.advance(Duration.ofMinutes(10));
        fail("ravi", 20);
        clock.advance(Duration.ofMinutes(6));
        assertThat(throttle.isLocked("ravi")).isFalse();      // 15 minutes after the lock began, not after the last try
    }

    @Test
    void usernamesAreComparedIgnoringCaseAndSpaces() {
        fail("Ravi", 3);
        fail(" ravi ", 2);
        assertThat(throttle.isLocked("RAVI")).isTrue();
    }

    @Test
    void oneUsersFailuresDoNotLockAnother() {
        fail("ravi", 5);
        assertThat(throttle.isLocked("sita")).isFalse();
    }
}
