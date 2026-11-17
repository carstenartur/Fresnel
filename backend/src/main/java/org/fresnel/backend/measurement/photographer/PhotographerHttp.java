package org.fresnel.backend.measurement.photographer;

import org.fresnel.measurement.CaptureProvider.FailureCode;
import org.fresnel.measurement.CaptureProviderException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.Map;
import java.util.Set;

final class PhotographerHttp {
    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_RESPONSE = 1024 * 1024;
    static final String BASE = "/api/v1/integrations/fresnel";

    record Reply(int status, JsonNode body) {}

    static Reply json(PhotographerPairing.Connection pairing, String path, String key,
                      byte[] body, int... accepted) {
        HttpURLConnection call = open(pairing, path, key, body, Map.of());
        try {
            int status = call.getResponseCode();
            for (int allowed : accepted) {
                if (allowed == status) {
                    return new Reply(status, status == 204 ? null : parse(call.getInputStream()));
                }
            }
            throw error(call, status);
        } catch (IOException ex) {
            throw transport();
        } finally {
            call.disconnect();
        }
    }

    static HttpURLConnection open(PhotographerPairing.Connection pairing, String path, String key,
                                  byte[] body, Map<String, String> headers) {
        HttpURLConnection call = null;
        try {
            call = (HttpURLConnection) pairing.origin().resolve(BASE + path).toURL().openConnection();
            call.setInstanceFollowRedirects(false);
            call.setConnectTimeout(3_000);
            call.setReadTimeout(8_000);
            call.setRequestProperty("Authorization", "Bearer " + pairing.bearerToken());
            call.setRequestProperty("Accept-Encoding", "identity");
            headers.forEach(call::setRequestProperty);
            if (key != null) {
                call.setRequestMethod("POST");
                call.setRequestProperty("Idempotency-Key", id(key));
                if (body != null) {
                    call.setDoOutput(true);
                    call.setFixedLengthStreamingMode(body.length);
                    call.setRequestProperty("Content-Type", "application/json");
                    try (var out = call.getOutputStream()) {
                        out.write(body);
                    }
                }
            }
            return call;
        } catch (IOException ex) {
            if (call != null) call.disconnect();
            throw transport();
        }
    }

    private static JsonNode parse(InputStream source) throws IOException {
        if (source == null) throw incompatible();
        try (source) {
            byte[] bytes = source.readNBytes(MAX_RESPONSE + 1);
            if (bytes.length > MAX_RESPONSE) throw incompatible();
            try {
                JsonNode node = JSON.readTree(bytes);
                if (node == null || node.isNull() || node.isMissingNode()) throw incompatible();
                return node;
            } catch (tools.jackson.core.JacksonException ex) {
                throw incompatible();
            }
        }
    }

    static CaptureProviderException error(HttpURLConnection call, int status) {
        String remote = "";
        try {
            remote = parse(call.getErrorStream()).path("code").asText("");
        } catch (IOException | CaptureProviderException ignored) {
            // A proxy or unavailable peer need not speak the protocol.
        }
        FailureCode code = switch (remote) {
            case "CAMERA_UNAVAILABLE", "CAMERA_NOT_FOUND" -> FailureCode.DEVICE_UNAVAILABLE;
            case "SESSION_NOT_FOUND" -> FailureCode.SESSION_NOT_FOUND;
            case "ASSET_NOT_FOUND" -> FailureCode.ASSET_NOT_FOUND;
            case "STEP_NOT_FOUND", "UNSUPPORTED_SETTINGS" -> FailureCode.INVALID_PLAN;
            case "INCOMPATIBLE" -> FailureCode.INCOMPATIBLE;
            case "INTEGRITY_MISMATCH" -> FailureCode.INTEGRITY_MISMATCH;
            default -> switch (status) {
                case 401, 403 -> FailureCode.AUTHENTICATION_FAILED;
                case 400, 413, 415, 422 -> FailureCode.INVALID_PLAN;
                case 409 -> FailureCode.CONFLICT;
                case 408, 429, 502, 503, 504 -> FailureCode.TRANSIENT_IO;
                case 404 -> FailureCode.UNAVAILABLE;
                default -> FailureCode.INCOMPATIBLE;
            };
        };
        String detail = Set.of("RECOVERY_REQUIRED", "IDEMPOTENCY_CONFLICT", "STEP_ALREADY_TRIGGERED",
                "LEASE_CONFLICT", "STEP_NOT_READY", "UNSUPPORTED_SETTINGS", "PATTERN_MISMATCH", "INSUFFICIENT_SCOPE").contains(remote)
                ? remote : code.name();
        return new CaptureProviderException(code, code == FailureCode.TRANSIENT_IO, detail);
    }

    static String id(String value) {
        if (value == null || value.length() > 128 || !org.fresnel.measurement.CaptureProvider.IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid capture identifier");
        }
        return value;
    }

    static CaptureProviderException transport() {
        return new CaptureProviderException(FailureCode.TRANSIENT_IO, true, "PHOTOGRAPHER_TRANSPORT_UNAVAILABLE");
    }

    static CaptureProviderException incompatible() {
        return new CaptureProviderException(FailureCode.INCOMPATIBLE, false, "PHOTOGRAPHER_PROTOCOL_MISMATCH");
    }

    static CaptureProviderException integrity() {
        return new CaptureProviderException(FailureCode.INTEGRITY_MISMATCH, false, "PHOTOGRAPHER_ASSET_MISMATCH");
    }
}
