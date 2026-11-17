package org.fresnel.backend.measurement;

import org.fresnel.measurement.CaptureProvider;
import org.fresnel.measurement.CaptureSource;
import org.springframework.stereotype.Component;

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

    private final List<CaptureSource.Info> sources;
    private final List<CaptureProvider> providers;
    private final Map<String, CaptureProvider> byId;

    public CaptureProviderRegistry(List<? extends CaptureSource> discoveredSources) {
        Objects.requireNonNull(discoveredSources, "discoveredSources");
        var metadata = new java.util.TreeMap<String,CaptureSource.Info>();
        var cameras = new java.util.TreeMap<String,CaptureProvider>();
        for (var source : discoveredSources) {
            var info = Objects.requireNonNull(source, "capture source").source();
            if (metadata.putIfAbsent(info.id(), info) != null)
                throw new IllegalStateException("Duplicate capture source id: " + info.id());
            if (source instanceof CaptureProvider camera) cameras.put(info.id(),camera);
        }
        sources = List.copyOf(metadata.values());
        providers = List.copyOf(cameras.values());
        byId = Map.copyOf(cameras);
    }

    public List<CaptureSource.Info> sources() { return sources; }

    /** Remote or mock camera services used by the existing independent camera-health dashboard. */
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
