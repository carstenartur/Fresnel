package org.fresnel.measurement;

/** Trusted acquisition entry point. Upload and camera control have distinct operations. */
public interface CaptureSource {
    enum Kind { FILE_UPLOAD, CAMERA }

    record Info(String id, String displayName, Kind kind) {
        public Info {
            if (id == null || id.length() > 64 || !CaptureProvider.IDENTIFIER.matcher(id).matches()
                    || displayName == null || displayName.isBlank() || displayName.length() > 128 || kind == null)
                throw new IllegalArgumentException("Invalid capture source");
        }
    }

    Info source();
}
