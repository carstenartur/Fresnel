package org.fresnel.backend.measurement;

import org.fresnel.measurement.CapturePlan;
import org.fresnel.measurement.CaptureProvider;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureProviderRegistryTest {

    @Test
    void registrySortsProvidersAndProvidesImmutableLookup() {
        StubProvider zeta = new StubProvider("zeta");
        StubProvider alpha = new StubProvider("alpha");
        CaptureProviderRegistry registry = new CaptureProviderRegistry(List.of(zeta, alpha));

        assertEquals(List.of("alpha", "zeta"), registry.all().stream()
                .map(provider -> provider.descriptor().id())
                .toList());
        assertSame(alpha, registry.find("alpha").orElseThrow());
        assertSame(zeta, registry.require("zeta"));
        assertTrue(registry.find("missing").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> registry.require("missing"));
        assertThrows(UnsupportedOperationException.class, registry.all()::clear);
    }

    @Test
    void registryRejectsDuplicateIdsAndNullProviders() {
        assertThrows(IllegalStateException.class, () ->
                new CaptureProviderRegistry(List.of(
                        new StubProvider("same"), new StubProvider("same"))));

        java.util.ArrayList<CaptureProvider> providers = new java.util.ArrayList<>();
        providers.add(null);
        assertThrows(NullPointerException.class, () -> new CaptureProviderRegistry(providers));
        assertThrows(NullPointerException.class, () -> new CaptureProviderRegistry(null));
    }

    private static final class StubProvider implements CaptureProvider {
        private final Descriptor descriptor;

        private StubProvider(String id) {
            descriptor = new Descriptor(
                    id,
                    id,
                    1,
                    1,
                    Set.of(),
                    Set.of(CapturePlan.ImageFormat.PNG),
                    new Limits(1, 1, Duration.ofSeconds(1)));
        }

        @Override public Descriptor descriptor() { return descriptor; }
        @Override public Health health() {
            return new Health(HealthState.CONNECTED, Instant.EPOCH, "OK");
        }
        @Override public List<Device> listDevices() { return List.of(); }
        @Override public SessionRef createSession(CapturePlan plan, String key) {
            throw new UnsupportedOperationException();
        }
        @Override public SessionStatus getSession(String id) {
            throw new UnsupportedOperationException();
        }
        @Override public StepResult triggerStep(
                String sessionId, String stepId, StepTrigger trigger, String key) {
            throw new UnsupportedOperationException();
        }
        @Override public EventPage events(String id, long afterEventId) {
            throw new UnsupportedOperationException();
        }
        @Override public AssetMetadata getAssetMetadata(String sessionId, String assetId) {
            throw new UnsupportedOperationException();
        }
        @Override public InputStream openAsset(
                String sessionId, String assetId, AssetRead read) {
            throw new UnsupportedOperationException();
        }
        @Override public void cancel(String id, String key) {
            throw new UnsupportedOperationException();
        }
    }
}
