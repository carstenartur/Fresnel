package org.fresnel.backend.measurement.photographer;

import org.fresnel.measurement.CaptureProvider.FailureCode;
import org.fresnel.measurement.CaptureProviderException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class PhotographerDiscoveryTest {
    @Test void isolatesPairingsAndPermitsOneRecoveryProbeAfterRepeatedOutages() {
        var time = new AtomicLong();
        var gate = new PhotographerDiscovery(time::get);
        Runnable outage = () -> gate.read("camera-a", () -> { throw PhotographerHttp.transport(); });
        for (int count = 0; count < 3; count++) assertThrows(CaptureProviderException.class, outage::run);
        assertEquals("PHOTOGRAPHER_DISCOVERY_BACKOFF", assertThrows(CaptureProviderException.class,
                () -> gate.read("camera-a", () -> fail("contact during backoff"))).messageCode());
        time.addAndGet(TimeUnit.SECONDS.toNanos(3));
        assertEquals("connected", gate.read("camera-a", () -> {
            assertThrows(CaptureProviderException.class, () -> gate.read("camera-a", () -> fail("duplicate probe")));
            return "connected";
        }));
        for (int count = 0; count < 3; count++) assertThrows(CaptureProviderException.class, outage::run);
        assertEquals("ready",gate.read("new-pairing", () -> "ready"));
        for (int count = 0; count < 4; count++) assertEquals(FailureCode.AUTHENTICATION_FAILED,
                assertThrows(CaptureProviderException.class, () -> gate.read("new-pairing", () -> {
                    throw new CaptureProviderException(FailureCode.AUTHENTICATION_FAILED,false,"UNAUTHORIZED");
                })).code());
    }
}
