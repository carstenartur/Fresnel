package org.fresnel.measurement;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** Owner-scoped file intake. An uploaded exposure does not acquire a camera lease or issue a shutter command. */
public interface UploadCaptureSource<P,S> extends CaptureSource {
    S create(String owner, UUID sessionId, P plan) throws IOException;
    S put(String owner, UUID sessionId, String step, String mediaType, String sha256, InputStream input) throws IOException;
}
