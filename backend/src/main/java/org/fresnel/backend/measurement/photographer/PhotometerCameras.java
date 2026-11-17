package org.fresnel.backend.measurement.photographer;

import org.fresnel.measurement.CaptureProvider;
import org.fresnel.backend.measurement.CaptureProviderRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;
import static org.fresnel.measurement.CapturePlan.ImageFormat.PNG;
import static org.fresnel.measurement.CaptureProvider.Capability.*;

/** Keep camera identities scoped to the grant, even when switching Photographer instances. */
@Component
@ConditionalOnProperty(name="fresnel.capture.photographer.enabled", havingValue="true")
public class PhotometerCameras {
    private final PhotographerPairing pairing;
    private final CaptureProviderRegistry providers;
    PhotometerCameras(PhotographerPairing pairing, CaptureProviderRegistry providers) {
        this.pairing = pairing; this.providers = providers;
    }
    public record Grant(String binding, CaptureProvider.Descriptor descriptor, CaptureProvider provider) {
        public List<CaptureProvider.Device> devices() {
            return provider.listDevices().stream().filter(d -> d.state() == CaptureProvider.DeviceState.CONNECTED
                    && d.supportedFormats().contains(PNG) && d.supports(EXTERNAL_STEP_TRIGGER) && d.supports(STILL_CAPTURE)).toList();
        }
        public String selection(String device) { return hash(binding + "\n" + device); }
    }
    public <T> T use(Function<Grant,T> operation) {
        return pairing.withConnection(c -> operation.apply(new Grant(hash(c.origin()+"\n"+c.instanceId()+"\n"+c.pairingId()),
                c.descriptor(), providers.require(PhotographerCaptureProvider.ID))));
    }
    private static String hash(String text) {
        return HexFormat.of().formatHex(PhotographerJournal.sha256().digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
