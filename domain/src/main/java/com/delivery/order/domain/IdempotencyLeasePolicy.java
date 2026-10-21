package com.delivery.order.domain;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
public final class IdempotencyLeasePolicy {
    private IdempotencyLeasePolicy() {}
    public static Duration boundedLease(Duration value) {
        Duration candidate = value == null ? Duration.ofSeconds(30) : value;
        return candidate.compareTo(Duration.ofSeconds(5)) < 0 ? Duration.ofSeconds(5)
                : candidate.compareTo(Duration.ofMinutes(5)) > 0 ? Duration.ofMinutes(5) : candidate;
    }
    public static boolean live(Instant until, Instant now) { return until != null && until.isAfter(now); }
    public static boolean owned(UUID token, UUID storedToken) { return token.equals(storedToken); }
    public static boolean fingerprintMatches(String expected, String stored,
                                             String version, String storedVersion) {
        return expected.equals(stored) && version.equals(storedVersion);
    }
}
