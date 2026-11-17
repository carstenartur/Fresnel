package org.fresnel.backend.measurement.photographer;

import org.fresnel.measurement.CaptureProvider.AssetMetadata;
import org.fresnel.measurement.CaptureProvider.AssetRead;
import org.fresnel.measurement.CaptureProvider.ByteRange;
import org.fresnel.measurement.CaptureProvider.FullAsset;
import org.fresnel.measurement.CaptureProviderException;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.fresnel.backend.measurement.photographer.PhotographerHttp.*;

/** Stages complete sources on disk, recovering interrupted reads with the originally pinned validator. */
final class PhotographerMedia {
    private static final long DOWNLOAD_NANOS = TimeUnit.MINUTES.toNanos(5);

    static InputStream open(PhotographerPairing.Connection pairing, String path, AssetMetadata asset, AssetRead read) {
        Objects.requireNonNull(read, "read");
        long start = read instanceof ByteRange part ? part.offset() : 0;
        long end = read instanceof ByteRange part ? Math.min(asset.byteLength(), part.endExclusive()) : asset.byteLength();
        if (start >= end) throw new IllegalArgumentException("Range starts past the source");
        Path stage = null;
        boolean returned = false;
        try {
            stage = Files.createTempFile("fresnel-capture-", ".part");
            long deadline = System.nanoTime() + DOWNLOAD_NANOS;
            boolean full = read == FullAsset.INSTANCE;
            for (int attempt = 0; attempt < 3; attempt++) {
                long cursor = (full ? 0 : start) + Files.size(stage);
                long limit = full ? asset.byteLength() : end;
                if (cursor == limit) break;
                boolean range = cursor > 0 || !full;
                String etag = "\"" + asset.sha256() + "\"";
                Map<String, String> headers = range ? Map.of("Range", "bytes=" + cursor + "-" + (limit - 1), "If-Range", etag) : Map.of();
                HttpURLConnection call = PhotographerHttp.open(pairing, path, null, null, headers);
                try {
                    int status = call.getResponseCode();
                    if (status != 200 && status != 206) throw error(call, status);
                    if (!etag.equals(call.getHeaderField("ETag"))
                            || !asset.mediaType().equalsIgnoreCase(call.getContentType())
                            || (call.getContentEncoding() != null && !"identity".equalsIgnoreCase(call.getContentEncoding()))) {
                        throw integrity();
                    }
                    if (status == 206) {
                        String expected = "bytes " + cursor + "-" + (limit - 1) + "/" + asset.byteLength();
                        if (!range || !expected.equals(call.getHeaderField("Content-Range"))
                                || call.getContentLengthLong() != limit - cursor) throw integrity();
                    } else {
                        if (call.getContentLengthLong() != asset.byteLength()) throw integrity();
                        // If-Range fell back to the full representation. Discard the partial prefix.
                        full = true;
                        cursor = 0;
                        limit = asset.byteLength();
                    }
                    try (InputStream in = call.getInputStream();
                         var out = Files.newOutputStream(stage, status == 200
                                 ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND)) {
                        byte[] buffer = new byte[32 * 1024];
                        long remaining = limit - cursor;
                        while (remaining > 0) {
                            if (System.nanoTime() - deadline >= 0) throw transport();
                            int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                            if (n < 0) throw new EOFException();
                            out.write(buffer, 0, n);
                            remaining -= n;
                        }
                        if (in.read() != -1) throw integrity();
                    }
                } catch (IOException | CaptureProviderException ex) {
                    if (ex instanceof CaptureProviderException provider && !provider.retryable()) throw provider;
                    if (attempt == 2 || System.nanoTime() - deadline >= 0) throw transport();
                } finally {
                    call.disconnect();
                }
            }
            long expected = full ? asset.byteLength() : end - start;
            if (Files.size(stage) != expected) throw transport();
            if (full) {
                var digest = PhotographerJournal.sha256();
                try (var in = new DigestInputStream(Files.newInputStream(stage), digest)) {
                    in.transferTo(java.io.OutputStream.nullOutputStream());
                }
                if (!asset.sha256().equals(HexFormat.of().formatHex(digest.digest()))) throw integrity();
                if (read instanceof ByteRange) {
                    try (var channel = java.nio.channels.FileChannel.open(stage, StandardOpenOption.WRITE)) {
                        channel.truncate(end);
                    }
                }
            }
            var result = Files.newInputStream(stage, StandardOpenOption.DELETE_ON_CLOSE);
            try {
                if (full && start != 0) result.skipNBytes(start);
            } catch (IOException ex) {
                result.close();
                throw ex;
            }
            returned = true;
            return result;
        } catch (IOException ex) {
            throw transport();
        } finally {
            if (stage != null && !returned) {
                try {
                    Files.deleteIfExists(stage);
                } catch (IOException ignored) {
                    stage.toFile().deleteOnExit();
                }
            }
        }
    }
}
