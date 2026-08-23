package org.fresnel.backend.api;

import org.fresnel.backend.measurement.CaptureProviderRegistry;
import org.fresnel.measurement.CapturePlan;
import org.fresnel.measurement.CaptureProvider;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureProviderStatusControllerUnitTest {

    @Test
    void healthFailureIsSanitizedAsDisconnected() {
        CaptureProvider provider = new StubProvider("broken") {
            @Override public Health health() { throw new IllegalStateException("secret URL"); }
        };
        CaptureProviderStatusController.Overview overview = controller(provider).overview();

        assertEquals(CaptureProvider.HealthState.DISCONNECTED, overview.state());
        assertEquals("CAPTURE_PROVIDERS_DISCONNECTED", overview.messageCode());
        assertEquals("PROVIDER_HEALTH_UNAVAILABLE",
                overview.providers().getFirst().messageCode());
    }

    @Test
    void deviceDiscoveryFailureDegradesAnOtherwiseConnectedProvider() {
        CaptureProvider provider = new StubProvider("partial") {
            @Override public List<Device> listDevices() {
                throw new IllegalStateException("private camera address");
            }
        };
        CaptureProviderStatusController.Overview overview = controller(provider).overview();

        assertEquals(CaptureProvider.HealthState.DEGRADED, overview.state());
        assertEquals("DEVICE_DISCOVERY_UNAVAILABLE",
                overview.providers().getFirst().messageCode());
        assertTrue(overview.providers().getFirst().devices().isEmpty());
    }

    @Test
    void mixedConnectedAndDisconnectedProvidersAreAggregatedAsDegraded() {
        CaptureProvider connected = new StubProvider("connected");
        CaptureProvider disconnected = new StubProvider("offline") {
            @Override public Health health() {
                return new Health(HealthState.DISCONNECTED, Instant.EPOCH, "OFFLINE");
            }
        };

        CaptureProviderStatusController.Overview overview =
                controller(connected, disconnected).overview();
        assertEquals(CaptureProvider.HealthState.DEGRADED, overview.state());
        assertEquals("CAPTURE_PROVIDER_SET_DEGRADED", overview.messageCode());
    }

    @Test
    void authenticationFailureHasPriorityWhenNoProviderIsUsable() {
        CaptureProvider authFailure = new StubProvider("auth") {
            @Override public Health health() {
                return new Health(
                        HealthState.AUTHENTICATION_FAILED, Instant.EPOCH, "TOKEN_REJECTED");
            }
        };
        CaptureProvider disconnected = new StubProvider("offline") {
            @Override public Health health() {
                return new Health(HealthState.DISCONNECTED, Instant.EPOCH, "OFFLINE");
            }
        };

        CaptureProviderStatusController.Overview overview =
                controller(authFailure, disconnected).overview();
        assertEquals(CaptureProvider.HealthState.AUTHENTICATION_FAILED, overview.state());
        assertEquals("CAPTURE_PROVIDER_AUTHENTICATION_FAILED", overview.messageCode());
    }

    private static CaptureProviderStatusController controller(CaptureProvider... providers) {
        return new CaptureProviderStatusController(
                new CaptureProviderRegistry(List.of(providers)));
    }

    private static class StubProvider implements CaptureProvider {
        private final Descriptor descriptor;

        private StubProvider(String id) {
            descriptor = new Descriptor(
                    id,
                    id,
                    1,
                    1,
                    Set.of(Capability.STILL_CAPTURE),
                    Set.of(CapturePlan.ImageFormat.PNG),
                    new Limits(8, 1024, Duration.ofMinutes(1)));
        }

        @Override public Descriptor descriptor() { return descriptor; }
        @Override public Health health() {
            return new Health(HealthState.CONNECTED, Instant.EPOCH, "CONNECTED");
        }
        @Override public List<Device> listDevices() {
            return List.of(new Device(
                    "camera-1", "Camera", DeviceState.CONNECTED,
                    descriptor.capabilities(), descriptor.supportedFormats()));
        }
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
