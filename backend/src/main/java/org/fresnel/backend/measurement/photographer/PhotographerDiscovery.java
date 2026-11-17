package org.fresnel.backend.measurement.photographer;

import org.fresnel.measurement.CaptureProvider.FailureCode;
import org.fresnel.measurement.CaptureProviderException;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Back off public discovery traffic during outages. Operator capture and cancellation remain available. */
final class PhotographerDiscovery {
    private final LongSupplier clock;
    private String grant;
    private long generation;
    private int failures;
    private long nextProbe;
    private boolean probing;

    PhotographerDiscovery(LongSupplier clock) { this.clock = clock; }

    <T> T read(String pairingId, Supplier<T> operation) {
        long ticket = enter(pairingId);
        boolean unavailable = false;
        try {
            return operation.get();
        } catch (CaptureProviderException failure) {
            unavailable = failure.code() == FailureCode.TRANSIENT_IO;
            throw failure;
        } finally {
            record(ticket, unavailable);
        }
    }

    private synchronized long enter(String pairingId) {
        if (!pairingId.equals(grant)) {
            grant = pairingId;
            failures = 0;
            probing = false;
            generation++;
        }
        if (failures >= 3) {
            if (probing || clock.getAsLong() - nextProbe < 0)
                throw new CaptureProviderException(FailureCode.TRANSIENT_IO, true, "PHOTOGRAPHER_DISCOVERY_BACKOFF");
            probing = true;
        }
        return generation;
    }

    private synchronized void record(long ticket, boolean unavailable) {
        if (ticket != generation) return;
        probing = false;
        if (!unavailable) {
            failures = 0;
            return;
        }
        failures = Math.min(7, failures + 1);
        if (failures >= 3) {
            long delayMillis = Math.min(30_000L, 2_000L << (failures - 3));
            nextProbe = clock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(
                    ThreadLocalRandom.current().nextLong(delayMillis / 2, delayMillis + 1));
            generation++;
        }
    }
}
