package org.fresnel.backend.measurement.photographer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** An authenticated, versioned envelope for the server-side Photographer grant. */
final class PairingEnvelope {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] CONTEXT = "Fresnel/Photographer/pairing/v1".getBytes(StandardCharsets.US_ASCII);
    private final Path keyFile;
    private final boolean supplied;

    PairingEnvelope(Path directory, String configuredKey) {
        supplied = !configuredKey.isBlank();
        keyFile = supplied ? Path.of(configuredKey) : directory.resolve("pairing.key");
    }

    byte[] seal(JsonMapper json, byte[] plaintext) throws IOException {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        var node = json.createObjectNode().put("version", 1)
                .put("nonce", Base64.getEncoder().encodeToString(nonce))
                .put("ciphertext", Base64.getEncoder().encodeToString(crypt(Cipher.ENCRYPT_MODE, nonce, plaintext)));
        return json.writeValueAsBytes(node);
    }

    byte[] open(JsonNode node) throws IOException {
        try {
            if (node.required("version").asInt() != 1) throw new IOException("Unsupported pairing envelope");
            byte[] nonce = Base64.getDecoder().decode(node.required("nonce").asString());
            if (nonce.length != 12) throw new IOException("Invalid pairing nonce");
            return crypt(Cipher.DECRYPT_MODE, nonce, Base64.getDecoder().decode(node.required("ciphertext").asString()));
        } catch (IllegalArgumentException e) { throw new IOException("Invalid pairing envelope"); }
    }

    private byte[] crypt(int mode, byte[] nonce, byte[] input) throws IOException {
        if (mode == Cipher.ENCRYPT_MODE && !supplied && Files.notExists(keyFile)) createKey();
        byte[] key;
        try (var stream = Files.newInputStream(keyFile)) { key = stream.readNBytes(33); }
        try {
            if (key.length != 32) throw new IOException("Invalid pairing key");
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(CONTEXT);
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) { throw new IOException("Pairing credential unavailable"); }
        finally { Arrays.fill(key, (byte) 0); }
    }

    // The caller holds the pairing file lock. A crash before rename leaves the active key untouched.
    private void createKey() throws IOException {
        Path pending = Files.createTempFile(keyFile.getParent(), ".key-", ".tmp");
        byte[] key = new byte[32];
        try {
            RANDOM.nextBytes(key);
            Files.write(pending, key, StandardOpenOption.WRITE, StandardOpenOption.SYNC);
            Files.move(pending, keyFile, StandardCopyOption.ATOMIC_MOVE);
        } finally { Arrays.fill(key, (byte) 0); Files.deleteIfExists(pending); }
    }
}
