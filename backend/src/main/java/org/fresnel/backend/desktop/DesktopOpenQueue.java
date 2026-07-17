package org.fresnel.backend.desktop;

import org.fresnel.backend.api.FresnelJobDocument;
import org.fresnel.backend.api.FresnelJobService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory, bounded store for desktop-open results.
 *
 * <p>Tokens are high-entropy, expire quickly and can be consumed exactly once.
 * Invalid job data is retained as a safe error result so the GUI can explain the
 * failure without crashing the primary Fresnel process.</p>
 */
@Component
@ConditionalOnProperty(name = "fresnel.desktop.enabled", havingValue = "true")
public class DesktopOpenQueue {

    static final int DEFAULT_MAX_PENDING = 64;
    private static final int TOKEN_BYTES = 32;

    private final FresnelJobService jobService;
    private final Clock clock;
    private final SecureRandom random;
    private final Duration timeToLive;
    private final int maxPending;
    private final LinkedHashMap<String, PendingOpen> pending = new LinkedHashMap<>();

    @Autowired
    public DesktopOpenQueue(
            FresnelJobService jobService,
            @Value("${fresnel.desktop.open-token-ttl-seconds:300}") long ttlSeconds,
            @Value("${fresnel.desktop.max-pending-opens:64}") int maxPending) {
        this(jobService, Clock.systemUTC(), new SecureRandom(),
                Duration.ofSeconds(ttlSeconds), maxPending);
    }

    DesktopOpenQueue(
            FresnelJobService jobService,
            Clock clock,
            SecureRandom random,
            Duration timeToLive,
            int maxPending) {
        if (jobService == null) throw new IllegalArgumentException("jobService must not be null");
        if (clock == null) throw new IllegalArgumentException("clock must not be null");
        if (random == null) throw new IllegalArgumentException("random must not be null");
        if (timeToLive == null || timeToLive.isZero() || timeToLive.isNegative()) {
            throw new IllegalArgumentException("desktop open token TTL must be positive");
        }
        if (maxPending < 1 || maxPending > 1024) {
            throw new IllegalArgumentException("desktop max pending opens must be between 1 and 1024");
        }
        this.jobService = jobService;
        this.clock = clock;
        this.random = random;
        this.timeToLive = timeToLive;
        this.maxPending = maxPending;
    }

    /** Validates and stores one job payload, returning an opaque one-time token. */
    public synchronized String enqueue(byte[] jobBytes) {
        if (jobBytes == null || jobBytes.length == 0) {
            return store(DesktopOpenResult.invalid("EMPTY_JOB", "The Fresnel job is empty."));
        }
        if (jobBytes.length > FresnelJobDocument.MAX_FILE_BYTES) {
            throw new IllegalArgumentException(
                    "Fresnel job exceeds the maximum size of "
                            + FresnelJobDocument.MAX_FILE_BYTES + " bytes");
        }

        DesktopOpenResult result;
        try {
            result = DesktopOpenResult.valid(jobService.parseAndNormalize(jobBytes));
        } catch (IllegalArgumentException e) {
            result = DesktopOpenResult.invalid("INVALID_JOB", safeMessage(e));
        }
        return store(result);
    }

    /** Stores a launcher-side read failure without exposing its source path. */
    public synchronized String enqueueError(String code, String message) {
        return store(DesktopOpenResult.invalid(code, message));
    }

    /** Removes and returns a token exactly once; expired and unknown tokens are absent. */
    public synchronized Optional<DesktopOpenResult> consume(String token) {
        cleanupExpired();
        if (!isTokenShapeValid(token)) {
            return Optional.empty();
        }
        PendingOpen entry = pending.remove(token);
        if (entry == null || !entry.expiresAt().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(entry.result());
    }

    synchronized int pendingCount() {
        cleanupExpired();
        return pending.size();
    }

    private String store(DesktopOpenResult result) {
        cleanupExpired();
        while (pending.size() >= maxPending) {
            Iterator<Map.Entry<String, PendingOpen>> iterator = pending.entrySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }

        String token;
        do {
            byte[] bytes = new byte[TOKEN_BYTES];
            random.nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (pending.containsKey(token));

        pending.put(token, new PendingOpen(result, clock.instant().plus(timeToLive)));
        return token;
    }

    private void cleanupExpired() {
        Instant now = clock.instant();
        pending.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    static boolean isTokenShapeValid(String token) {
        if (token == null || token.length() < 40 || token.length() > 128) return false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!(c >= 'A' && c <= 'Z')
                    && !(c >= 'a' && c <= 'z')
                    && !(c >= '0' && c <= '9')
                    && c != '-' && c != '_') {
                return false;
            }
        }
        return true;
    }

    private static String safeMessage(IllegalArgumentException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "The Fresnel job is invalid.";
        int newline = message.indexOf('\n');
        String firstLine = newline >= 0 ? message.substring(0, newline) : message;
        return firstLine.length() <= 500 ? firstLine : firstLine.substring(0, 500);
    }

    private record PendingOpen(DesktopOpenResult result, Instant expiresAt) {}
}
