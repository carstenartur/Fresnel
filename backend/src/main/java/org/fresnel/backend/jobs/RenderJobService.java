package org.fresnel.backend.jobs;

import jakarta.annotation.PreDestroy;
import org.fresnel.backend.persistence.RenderJobEntity;
import org.fresnel.backend.persistence.RenderJobRepository;
import org.fresnel.optics.PngExporter;
import org.fresnel.optics.RenderResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Render-job registry with progress reporting, persistence and object-level
 * authorization.
 *
 * <p>Every job has an authenticated owner. Live and rehydrated lookups apply the
 * same owner-or-administrator policy, and unauthorized identifiers are exposed
 * as absent so callers cannot probe whether another user's job exists.</p>
 */
@Service
public class RenderJobService {

    public static final long JOB_TTL_MS = 30 * 60 * 1000L;
    public static final int WORKERS = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    public static final int JOB_ID_ENTROPY_BYTES = 24; // 192 bits

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ID_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final ExecutorService executor = Executors.newFixedThreadPool(WORKERS, runnable -> {
        Thread thread = new Thread(runnable, "render-job");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentMap<String, RenderJob> jobs = new ConcurrentHashMap<>();

    private final RenderJobRepository repository;
    private final Duration persistedRetention;

    @Autowired
    public RenderJobService(
            RenderJobRepository repository,
            @Value("${fresnel.jobs.persisted-retention-days:30}") long persistedRetentionDays) {
        if (persistedRetentionDays < 1) {
            throw new IllegalArgumentException("fresnel.jobs.persisted-retention-days must be positive");
        }
        this.repository = repository;
        this.persistedRetention = Duration.ofDays(persistedRetentionDays);
    }

    /** Submit a new render job for the current authenticated principal. */
    public RenderJob submit(String label, Function<RenderJob, RenderResult> work) {
        String ownerId = currentOwnerOrNull();
        if (ownerId == null) {
            throw new IllegalStateException("Render jobs require an authenticated owner");
        }

        RenderJob job;
        do {
            job = new RenderJob(newJobId(), label, ownerId);
        } while (jobs.putIfAbsent(job.id(), job) != null);

        try {
            repository.save(new RenderJobEntity(job.id(), label, ownerId));
        } catch (RuntimeException ignored) {
            // Persistence remains best-effort while the live job is available.
        }

        RenderJob submitted = job;
        executor.submit(() -> {
            try {
                RenderResult result = work.apply(submitted);
                submitted.complete(result);
                persistTerminal(submitted);
            } catch (Throwable failure) {
                submitted.fail(failure);
                persistTerminal(submitted);
            } finally {
                reapExpiredJobs();
            }
        });
        return submitted;
    }

    /**
     * Returns a live or persisted job only when the requester owns it or is an
     * administrator. Unknown and unauthorized identifiers both return empty.
     */
    public Optional<RenderJob> findAuthorized(
            String id,
            String requesterId,
            boolean administrator) {
        if (id == null || id.isBlank() || requesterId == null || requesterId.isBlank()) {
            return Optional.empty();
        }
        RenderJob live = jobs.get(id);
        if (live != null) {
            return mayRead(live.ownerId(), requesterId, administrator)
                    ? Optional.of(live)
                    : Optional.empty();
        }
        return repository.findById(id)
                .filter(entity -> mayRead(entity.getOwnerId(), requesterId, administrator))
                .map(RenderJobService::rehydrate);
    }

    /** Returns an authorized persisted PNG, if present. */
    public Optional<byte[]> resultPngAuthorized(
            String id,
            String requesterId,
            boolean administrator) {
        if (id == null || id.isBlank() || requesterId == null || requesterId.isBlank()) {
            return Optional.empty();
        }
        return repository.findById(id)
                .filter(entity -> mayRead(entity.getOwnerId(), requesterId, administrator))
                .map(RenderJobEntity::getResultPng)
                .filter(bytes -> bytes != null && bytes.length > 0);
    }

    /** Internal removal hook; no public HTTP delete endpoint is exposed. */
    public void remove(String id) {
        jobs.remove(id);
        repository.deleteById(id);
    }

    private String newJobId() {
        byte[] entropy = new byte[JOB_ID_ENTROPY_BYTES];
        RANDOM.nextBytes(entropy);
        return "j-" + ID_ENCODER.encodeToString(entropy);
    }

    private void reapExpiredJobs() {
        Instant now = Instant.now();
        long liveCutoff = now.toEpochMilli() - JOB_TTL_MS;
        jobs.entrySet().removeIf(entry -> entry.getValue().createdAtEpochMs() < liveCutoff);
        try {
            repository.deleteByFinishedAtBefore(now.minus(persistedRetention));
        } catch (RuntimeException ignored) {
            // Retention cleanup must not change the render result seen by clients.
        }
    }

    private void persistTerminal(RenderJob job) {
        try {
            RenderJobEntity entity = repository.findById(job.id())
                    .orElseGet(() -> new RenderJobEntity(job.id(), job.label(), job.ownerId()));
            entity.setOwnerId(job.ownerId());
            entity.setProgress(job.progress());
            entity.setMessage(job.message());
            entity.setFinishedAt(Instant.now());
            if (job.state() == RenderJob.State.COMPLETED) {
                entity.setState(RenderJobEntity.State.COMPLETED);
                RenderResult result = job.result();
                if (result != null) {
                    double dpi = 25.4 / result.pixelSizeMm();
                    entity.setResultPng(PngExporter.toPngBytes(result, dpi));
                    entity.setResultPixelSizeMm(result.pixelSizeMm());
                    entity.setResultWidthPx(result.widthPx());
                    entity.setResultHeightPx(result.heightPx());
                }
            } else {
                entity.setState(RenderJobEntity.State.FAILED);
                Throwable failure = job.error();
                if (failure != null) {
                    String message = failure.getMessage();
                    entity.setErrorMessage(message == null
                            ? failure.getClass().getSimpleName()
                            : message.substring(0, Math.min(2048, message.length())));
                }
            }
            repository.save(entity);
        } catch (IOException | RuntimeException ignored) {
            // The in-memory job remains available to authorized active clients.
        }
    }

    private static RenderJob rehydrate(RenderJobEntity entity) {
        RenderJob job = new RenderJob(entity.getId(), entity.getLabel(), entity.getOwnerId());
        switch (entity.getState()) {
            case COMPLETED -> job.markCompletedExternally(entity.getProgress(), entity.getMessage());
            case FAILED -> job.markFailedExternally(entity.getMessage(), entity.getErrorMessage());
            case RUNNING -> job.reportProgress(entity.getProgress(), entity.getMessage());
            case QUEUED -> { /* no transition required */ }
        }
        return job;
    }

    private static boolean mayRead(
            String ownerId,
            String requesterId,
            boolean administrator) {
        return administrator || (ownerId != null && ownerId.equals(requesterId));
    }

    private static String currentOwnerOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) return null;
        String name = authentication.getName();
        return name == null || "anonymousUser".equals(name) ? null : name;
    }

    /** Gracefully stop worker threads when the Spring context closes. */
    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
