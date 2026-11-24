package com.orderaudit.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Slows down password guessing: after 5 wrong passwords for the same username within 15 minutes, that username is
 * locked for 15 minutes (even the right password is refused). A good sign-in clears the count. Kept in memory, so a
 * restart clears it, which is fine for stopping guessing.
 */
@Component
public class LoginThrottle {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final Duration LOCK = Duration.ofMinutes(15);
    private static final int MAX_TRACKED = 10_000;

    private record State(int failures, Instant firstFailure, Instant lockedUntil) {
    }

    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();
    private volatile Clock clock = Clock.systemUTC();

    /** Tests move the clock to check that a lock runs out. */
    void useClock(Clock clock) {
        this.clock = clock;
    }

    private static String key(String username) {
        String k = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return k.length() > 100 ? k.substring(0, 100) : k;
    }

    public boolean isLocked(String username) {
        State s = states.get(key(username));
        return s != null && s.lockedUntil() != null && clock.instant().isBefore(s.lockedUntil());
    }

    public void recordFailure(String username) {
        Instant now = clock.instant();
        if (states.size() > MAX_TRACKED) {
            states.entrySet().removeIf(e -> isStale(e.getValue(), now));   // unknown names must not fill the memory
        }
        states.compute(key(username), (k, old) -> {
            if (old == null || isStale(old, now)) {
                return new State(1, now, null);
            }
            if (old.lockedUntil() != null) {
                return old;                                   // already locked: attempts while locked change nothing
            }
            int failures = old.failures() + 1;
            return new State(failures, old.firstFailure(), failures >= MAX_FAILURES ? now.plus(LOCK) : null);
        });
    }

    public void recordSuccess(String username) {
        states.remove(key(username));
    }

    private static boolean isStale(State s, Instant now) {
        if (s.lockedUntil() != null) {
            return !now.isBefore(s.lockedUntil());
        }
        return !now.isBefore(s.firstFailure().plus(WINDOW));
    }
}
