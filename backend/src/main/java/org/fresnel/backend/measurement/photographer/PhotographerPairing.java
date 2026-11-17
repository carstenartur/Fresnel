package org.fresnel.backend.measurement.photographer;

import org.fresnel.backend.desktop.DesktopDataDirectory;
import org.fresnel.measurement.CaptureProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Server-side credential and instance binding for the administrator-configured Photographer origin. */
@Component
@ConditionalOnProperty(name = "fresnel.capture.photographer.enabled", havingValue = "true")
public final class PhotographerPairing {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._~+/-]+=*");
    private static final Set<String> SCOPES = Set.of("fresnel:descriptor:read", "fresnel:cameras:read",
            "fresnel:capture-sessions:write", "fresnel:media:read");
    private static final int MAX_REPLY_BYTES = 64 * 1024;
    private final URI origin;
    private final Path directory;
    private final Path manifest;

    @Autowired
    PhotographerPairing(@Value("${fresnel.capture.photographer.origin:}") String configuredOrigin,
                        @Value("${fresnel.capture.photographer.trust-private-network:false}") boolean trustNetwork,
                        @Value("${fresnel.capture.photographer.directory:}") String directory) {
        this(configuredOrigin, trustNetwork, directory.isBlank()
                ? DesktopDataDirectory.resolve().resolve("photographer") : Path.of(directory));
    }

    PhotographerPairing(String configuredOrigin, boolean trustNetwork, Path directory) {
        this.origin = origin(configuredOrigin, trustNetwork);
        this.directory = directory;
        this.manifest = directory.resolve("connection.json");
    }

    public record Connection(URI origin, String instanceId, String pairingId, String bearerToken,
                             List<String> scopes, CaptureProvider.Descriptor descriptor) {
        public Connection { scopes = List.copyOf(scopes); }
        @Override public String toString() { return "Photographer connection " + pairingId; }
    }
    public record Status(boolean paired, boolean originMatches, String instanceId, String pairingId, List<String> scopes) {}
    private record Stored(String fresnelInstanceId, Connection connection) {
        @Override public String toString() { return "Stored Photographer pairing"; }
    }
    public static final class Failure extends RuntimeException {
        private final int status;
        Failure(int status, String code) { super(code); this.status = status; }
        public int status() { return status; }
    }

    public Optional<Connection> current() {
        Stored stored = read();
        return Optional.ofNullable(stored).map(Stored::connection).filter(c -> origin.equals(c.origin()));
    }

    public Status status() {
        Stored stored = read();
        if (stored == null || stored.connection() == null) return new Status(false, true, null, null, List.of());
        Connection c = stored.connection();
        return new Status(true, origin.equals(c.origin()), c.instanceId(), c.pairingId(), c.scopes());
    }

    public synchronized void pair(String code) {
        if (code == null || code.isBlank() || code.length() > 512) throw new Failure(400, "INVALID_PAIRING_CODE");
        change(() -> {
            Stored stored = read();
            if (stored != null && stored.connection() != null) throw new Failure(409, "ALREADY_PAIRED");
            if (stored == null) {
                stored = new Stored(UUID.randomUUID().toString(), null);
                save(stored);
            }
            JsonNode grant = exchange(code, stored.fresnelInstanceId());
            save(new Stored(stored.fresnelInstanceId(), connection(grant)));
        });
    }

    /** Remove the local token. The authenticated Photographer administrator controls remote revocation. */
    public synchronized void forget() {
        change(() -> {
            Stored stored = read();
            if (stored != null) save(new Stored(stored.fresnelInstanceId(), null));
        });
    }

    private JsonNode exchange(String code, String localId) {
        String version = PhotographerPairing.class.getPackage().getImplementationVersion();
        byte[] bytes = JSON.writeValueAsBytes(Map.of("pairingCode", code, "fresnelInstanceId", localId,
                "fresnelDisplayName", "Fresnel", "fresnelApplicationVersion", version == null ? "development" : version,
                "fresnelProtocolVersion", 1));
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) origin.resolve(PhotographerHttp.BASE + "/pair").toURL().openConnection();
            http.setInstanceFollowRedirects(false);
            http.setUseCaches(false);
            http.setConnectTimeout(4_000);
            http.setReadTimeout(8_000);
            http.setDoOutput(true);
            http.setRequestMethod("POST");
            http.setRequestProperty("Content-Type", "application/json");
            http.setRequestProperty("Accept", "application/json");
            http.setRequestProperty("Idempotency-Key", UUID.randomUUID().toString());
            http.setFixedLengthStreamingMode(bytes.length);
            try (var output = http.getOutputStream()) { output.write(bytes); }
            int status = http.getResponseCode();
            if (status == 403) throw new Failure(400, "PAIRING_CODE_INVALID");
            if (status == 409) throw new Failure(409, "INCOMPATIBLE");
            if (status == 429) throw new Failure(429, "RATE_LIMITED");
            if (status != 201) throw new Failure(502, "PAIRING_EXCHANGE_FAILED");
            try (var input = http.getInputStream()) {
                byte[] response = input.readNBytes(MAX_REPLY_BYTES + 1);
                if (response.length > MAX_REPLY_BYTES) throw new Failure(502, "INVALID_PAIRING_RESPONSE");
                return JSON.readTree(response);
            }
        } catch (IOException e) { throw new Failure(502, "PHOTOGRAPHER_UNAVAILABLE"); }
        catch (tools.jackson.core.JacksonException e) { throw new Failure(502, "INVALID_PAIRING_RESPONSE"); }
        finally { if (http != null) http.disconnect(); }
    }

    private Connection connection(JsonNode grant) {
        try {
            String pairingId = field(grant, "pairingId", ID, 128);
            String token = field(grant, "bearerToken", TOKEN, 512);
            JsonNode wire = grant.required("descriptor");
            String instanceId = field(wire, "instanceId", ID, 128);
            if (wire.required("minimumSupportedProtocolVersion").asInt() > 1
                    || wire.required("protocolVersion").asInt() < 1) throw new Failure(409, "INCOMPATIBLE");
            var scopeList = new ArrayList<String>();
            JsonNode scopes = grant.required("scopes");
            if (!scopes.isArray()) throw new IllegalArgumentException();
            for (JsonNode scope : scopes) {
                if (!scope.isString() || !SCOPES.contains(scope.asString()) || scopeList.contains(scope.asString()))
                    throw new IllegalArgumentException();
                scopeList.add(scope.asString());
            }
            return new Connection(origin, instanceId, pairingId, token, scopeList,
                    PhotographerCaptureProvider.descriptor(wire));
        } catch (Failure e) { throw e; }
        catch (RuntimeException e) { throw new Failure(502, "INVALID_PAIRING_RESPONSE"); }
    }

    private static String field(JsonNode node, String name, Pattern pattern, int max) {
        JsonNode value = node.required(name);
        if (!value.isString() || value.asString().length() > max || !pattern.matcher(value.asString()).matches())
            throw new IllegalArgumentException();
        return value.asString();
    }

    private Stored read() {
        try {
            if (Files.notExists(manifest)) return null;
            return JSON.readValue(Files.readAllBytes(manifest), Stored.class);
        } catch (IOException | tools.jackson.core.JacksonException e) { throw new Failure(500, "PAIRING_STORE_UNAVAILABLE"); }
    }

    private void change(Runnable action) {
        try {
            if (directory.getFileSystem().supportedFileAttributeViews().contains("posix"))
                Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            else Files.createDirectories(directory);
            try (var channel = FileChannel.open(directory.resolve("pairing.lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE); var lock = channel.lock()) {
                action.run();
            }
        } catch (IOException e) { throw new Failure(500, "PAIRING_STORE_UNAVAILABLE"); }
    }

    private void save(Stored stored) {
        try {
            // createTempFile uses owner-only permissions on POSIX. Replacement publishes a complete snapshot.
            Path temp = Files.createTempFile(directory, ".pairing-", ".tmp");
            try {
                Files.write(temp, JSON.writeValueAsBytes(stored), StandardOpenOption.WRITE, StandardOpenOption.SYNC);
                Files.move(temp, manifest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temp); }
        } catch (IOException e) { throw new Failure(500, "PAIRING_STORE_UNAVAILABLE"); }
    }

    private static URI origin(String value, boolean trustNetwork) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            boolean local = Set.of("127.0.0.1", "localhost", "[::1]", "[0:0:0:0:0:0:0:1]").contains(
                    host == null ? "" : host.toLowerCase(java.util.Locale.ROOT));
            boolean secure = "https".equalsIgnoreCase(uri.getScheme())
                    || ("http".equalsIgnoreCase(uri.getScheme()) && (local || trustNetwork));
            if (secure && host != null && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                    && uri.getRawFragment() == null && uri.getPort() != 0 && uri.getPort() <= 65535
                    && ("".equals(uri.getRawPath()) || "/".equals(uri.getRawPath())))
                return URI.create(uri.getScheme().toLowerCase(java.util.Locale.ROOT) + "://" + uri.getRawAuthority());
        } catch (IllegalArgumentException ignored) { /* Never include configured URLs in diagnostics. */ }
        throw new IllegalArgumentException("Configure a trusted Photographer HTTPS origin or a loopback origin");
    }
}
