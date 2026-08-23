package org.fresnel.backend.measurement;

import org.fresnel.measurement.CaptureProvider;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable registry of application-owned capture-provider adapters.
 *
 * <p>Provider IDs come only from validated {@link CaptureProvider.Descriptor}
 * instances supplied as Spring beans. Remote configuration and measurement jobs
 * cannot introduce implementation classes or arbitrary provider URLs.</p>
 */
@Component
public final class CaptureProviderRegistry {

    private final List<CaptureProvider> providers;
    private final Map<String, CaptureProvider> byId;

    public CaptureProviderRegistry(List<CaptureProvider> discoveredProviders) {
        Objects.requireNonNull(discoveredProviders, "discoveredProviders");
        List<CaptureProvider> ordered = discoveredProviders.stream()
                .map(provider -> Objects.requireNonNull(provider, "capture provider"))
                .sorted(Comparator.comparing(provider -> provider.descriptor().id()))
                .toList();

        Map<String, CaptureProvider> index = new LinkedHashMap<>();
        for (CaptureProvider provider : ordered) {
            CaptureProvider.Descriptor descriptor = Objects.requireNonNull(
                    provider.descriptor(), "capture provider descriptor");
            CaptureProvider previous = index.putIfAbsent(descriptor.id(), provider);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate capture provider id: " + descriptor.id());
            }
        }
        this.providers = List.copyOf(ordered);
        this.byId = Map.copyOf(index);
    }

    public List<CaptureProvider> all() {
        return providers;
    }

    public Optional<CaptureProvider> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public CaptureProvider require(String id) {
        CaptureProvider provider = byId.get(id);
        if (provider == null) {
            throw new IllegalArgumentException("unknown capture provider id: " + id);
        }
        return provider;
    }
}
