package org.fresnel.backend.desktop;

import org.fresnel.backend.api.FresnelJobService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class DesktopOpenQueueTest {

    @Autowired FresnelJobService jobService;

    @Test
    void validJobCanBeConsumedExactlyOnce() {
        MutableClock clock = new MutableClock();
        DesktopOpenQueue queue = queue(clock, 4);

        String token = queue.enqueue(validJob());
        assertTrue(DesktopOpenQueue.isTokenShapeValid(token));
        DesktopOpenResult result = queue.consume(token).orElseThrow();
        assertTrue(result.valid());
        assertEquals("zone-plate", result.job().plugin().id());
        assertTrue(queue.consume(token).isEmpty());
    }

    @Test
    void invalidJobBecomesANonFatalGuiError() {
        DesktopOpenQueue queue = queue(new MutableClock(), 4);
        String token = queue.enqueue("{not-json".getBytes(StandardCharsets.UTF_8));

        DesktopOpenResult result = queue.consume(token).orElseThrow();
        assertFalse(result.valid());
        assertEquals("INVALID_JOB", result.errorCode());
        assertTrue(result.errorMessage().contains("Invalid Fresnel job JSON"));
    }

    @Test
    void tokensExpireAndMalformedTokensAreIgnored() {
        MutableClock clock = new MutableClock();
        DesktopOpenQueue queue = queue(clock, 4);
        String token = queue.enqueue(validJob());

        assertTrue(queue.consume("../../not-a-token").isEmpty());
        clock.advance(Duration.ofSeconds(6));
        assertTrue(queue.consume(token).isEmpty());
        assertEquals(0, queue.pendingCount());
    }

    @Test
    void boundedQueueEvictsTheOldestPendingEntry() {
        DesktopOpenQueue queue = queue(new MutableClock(), 2);
        String first = queue.enqueue(validJob());
        String second = queue.enqueue(validJob());
        String third = queue.enqueueError("READ_ERROR", "Could not read the selected job.");

        assertNotEquals(first, second);
        assertNotEquals(second, third);
        assertTrue(queue.consume(first).isEmpty());
        assertTrue(queue.consume(second).isPresent());
        DesktopOpenResult error = queue.consume(third).orElseThrow();
        assertFalse(error.valid());
        assertEquals("READ_ERROR", error.errorCode());
    }

    private DesktopOpenQueue queue(MutableClock clock, int maxPending) {
        return new DesktopOpenQueue(jobService, clock, new CountingSecureRandom(),
                Duration.ofSeconds(5), maxPending);
    }

    private static byte[] validJob() {
        return """
                {
                  "format": "io.github.carstenartur.fresnel.job",
                  "formatVersion": 1,
                  "plugin": {
                    "id": "zone-plate",
                    "parameterSchemaVersion": 1,
                    "algorithmVersion": "zone-plate/1"
                  },
                  "parameters": {
                    "apertureDiameterMm": 10,
                    "focalLengthMm": 1000,
                    "wavelengthNm": 550,
                    "dpi": 1200
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);
    }

    private static final class CountingSecureRandom extends SecureRandom {
        private int value = 1;

        @Override
        public void nextBytes(byte[] bytes) {
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) (value + i);
            }
            value++;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-07-17T12:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
