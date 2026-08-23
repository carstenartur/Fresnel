package org.fresnel.backend.api;

import org.fresnel.backend.measurement.CaptureProviderRegistry;
import org.fresnel.measurement.CapturePlan;
import org.fresnel.measurement.CaptureProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Public read-only view of configured capture services and redacted devices.
 *
 * <p>The endpoint intentionally exposes no provider URL, bearer token, camera
 * address, serial number, file path or raw exception text. It is the status
 * contract used by the Fresnel UI and later by measurement-plugin setup flows.</p>
 */
@RestController
@RequestMapping("/api/capture-providers")
public class CaptureProviderStatusController {

    private final CaptureProviderRegistry registry;

    public CaptureProviderStatusController(CaptureProviderRegistry registry) {
        this.registry = registry;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Overview overview() {
        List<ProviderStatus> providers = registry.all().stream()
                .map(CaptureProviderStatusController::snapshot)
                .toList();
        return Overview.from(providers);
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProviderStatus> provider(@PathVariable("id") String id) {
        return registry.find(id)
                .map(CaptureProviderStatusController::snapshot)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    private static ProviderStatus snapshot(CaptureProvider provider) {
        CaptureProvider.Descriptor descriptor = Objects.requireNonNull(
                provider.descriptor(), "capture provider descriptor");
        CaptureProvider.Health health;
        try {
            health = Objects.requireNonNull(provider.health(), "capture provider health");
        } catch (RuntimeException exception) {
            health = new CaptureProvider.Health(
                    CaptureProvider.HealthState.DISCONNECTED,
                    Instant.now(),
                    "PROVIDER_HEALTH_UNAVAILABLE");
        }

        List<DeviceStatus> devices;
        try {
            devices = provider.listDevices().stream()
                    .map(DeviceStatus::from)
                    .sorted(Comparator.comparing(DeviceStatus::displayName)
                            .thenComparing(DeviceStatus::id))
                    .toList();
        } catch (RuntimeException exception) {
            devices = List.of();
            if (health.usable()) {
                health = new CaptureProvider.Health(
                        CaptureProvider.HealthState.DEGRADED,
                        Instant.now(),
                        "DEVICE_DISCOVERY_UNAVAILABLE");
            }
        }

        return new ProviderStatus(
                descriptor.id(),
                descriptor.displayName(),
                health.state(),
                health.checkedAt(),
                health.messageCode(),
                descriptor.protocolVersion(),
                descriptor.minimumSupportedProtocolVersion(),
                descriptor.capabilities().stream()
                        .sorted(Comparator.comparing(Enum::name))
                        .toList(),
                descriptor.supportedFormats().stream()
                        .sorted(Comparator.comparing(Enum::name))
                        .toList(),
                new ProviderLimits(
                        descriptor.limits().maximumSteps(),
                        descriptor.limits().maximumAssetBytes(),
                        descriptor.limits().maximumSessionDuration().toSeconds()),
                devices);
    }

    public record Overview(
            CaptureProvider.HealthState state,
            Instant checkedAt,
            String messageCode,
            List<ProviderStatus> providers) {

        public Overview {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(checkedAt, "checkedAt");
            Objects.requireNonNull(messageCode, "messageCode");
            providers = List.copyOf(providers);
        }

        static Overview from(List<ProviderStatus> source) {
            List<ProviderStatus> providers = List.copyOf(source);
            if (providers.isEmpty()) {
                return new Overview(
                        CaptureProvider.HealthState.NOT_CONFIGURED,
                        Instant.now(),
                        "NO_CAPTURE_PROVIDER_CONFIGURED",
                        providers);
            }

            Instant checkedAt = providers.stream()
                    .map(ProviderStatus::checkedAt)
                    .max(Comparator.naturalOrder())
                    .orElseGet(Instant::now);
            long connected = providers.stream()
                    .filter(provider -> provider.state() == CaptureProvider.HealthState.CONNECTED)
                    .count();
            boolean usable = providers.stream().anyMatch(provider ->
                    provider.state() == CaptureProvider.HealthState.CONNECTED
                            || provider.state() == CaptureProvider.HealthState.DEGRADED);

            if (connected == providers.size()) {
                return new Overview(
                        CaptureProvider.HealthState.CONNECTED,
                        checkedAt,
                        "ALL_CAPTURE_PROVIDERS_CONNECTED",
                        providers);
            }
            if (usable) {
                return new Overview(
                        CaptureProvider.HealthState.DEGRADED,
                        checkedAt,
                        "CAPTURE_PROVIDER_SET_DEGRADED",
                        providers);
            }

            CaptureProvider.HealthState state = priorityState(providers);
            return new Overview(state, checkedAt, aggregateMessage(state), providers);
        }

        private static CaptureProvider.HealthState priorityState(List<ProviderStatus> providers) {
            CaptureProvider.HealthState[] priority = {
                    CaptureProvider.HealthState.PAIRING,
                    CaptureProvider.HealthState.AUTHENTICATION_FAILED,
                    CaptureProvider.HealthState.INCOMPATIBLE,
                    CaptureProvider.HealthState.DISCONNECTED,
                    CaptureProvider.HealthState.REVOKED,
                    CaptureProvider.HealthState.NOT_CONFIGURED
            };
            for (CaptureProvider.HealthState candidate : priority) {
                if (providers.stream().anyMatch(provider -> provider.state() == candidate)) {
                    return candidate;
                }
            }
            return CaptureProvider.HealthState.DISCONNECTED;
        }

        private static String aggregateMessage(CaptureProvider.HealthState state) {
            return switch (state) {
                case NOT_CONFIGURED -> "NO_CAPTURE_PROVIDER_CONFIGURED";
                case PAIRING -> "CAPTURE_PROVIDER_PAIRING";
                case CONNECTED -> "ALL_CAPTURE_PROVIDERS_CONNECTED";
                case DEGRADED -> "CAPTURE_PROVIDER_SET_DEGRADED";
                case DISCONNECTED -> "CAPTURE_PROVIDERS_DISCONNECTED";
                case INCOMPATIBLE -> "CAPTURE_PROVIDER_INCOMPATIBLE";
                case AUTHENTICATION_FAILED -> "CAPTURE_PROVIDER_AUTHENTICATION_FAILED";
                case REVOKED -> "CAPTURE_PROVIDER_REVOKED";
            };
        }
    }

    public record ProviderStatus(
            String id,
            String displayName,
            CaptureProvider.HealthState state,
            Instant checkedAt,
            String messageCode,
            int protocolVersion,
            int minimumSupportedProtocolVersion,
            List<CaptureProvider.Capability> capabilities,
            List<CapturePlan.ImageFormat> supportedFormats,
            ProviderLimits limits,
            List<DeviceStatus> devices) {

        public ProviderStatus {
            capabilities = List.copyOf(capabilities);
            supportedFormats = List.copyOf(supportedFormats);
            devices = List.copyOf(devices);
        }
    }

    public record ProviderLimits(
            int maximumSteps,
            long maximumAssetBytes,
            long maximumSessionDurationSeconds) {}

    public record DeviceStatus(
            String id,
            String displayName,
            CaptureProvider.DeviceState state,
            List<CaptureProvider.Capability> capabilities,
            List<CapturePlan.ImageFormat> supportedFormats) {

        public DeviceStatus {
            capabilities = List.copyOf(capabilities);
            supportedFormats = List.copyOf(supportedFormats);
        }

        static DeviceStatus from(CaptureProvider.Device device) {
            return new DeviceStatus(
                    device.id(),
                    device.displayName(),
                    device.state(),
                    device.capabilities().stream()
                            .sorted(Comparator.comparing(Enum::name))
                            .toList(),
                    device.supportedFormats().stream()
                            .sorted(Comparator.comparing(Enum::name))
                            .toList());
        }
    }
}
