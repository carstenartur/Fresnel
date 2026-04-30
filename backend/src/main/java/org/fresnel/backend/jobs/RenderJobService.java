package org.fresnel.backend.jobs;

import jakarta.annotation.PreDestroy;
import org.fresnel.backend.persistence.RenderJobEntity;
import org.fresnel.backend.persistence.RenderJobRepository;
import org.fresnel.optics.PngExporter;
import org.fresnel.optics.RenderResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Render-job registry with progress reporting via {@link RenderJob}.
 *
 * <p>In-flight jobs are kept in an in-memory map so SSE listeners can react to
 * progress in microseconds; on terminal state changes ({@code COMPLETED} /
 * {@code FAILED}) the job is persisted via {@link RenderJobRepository} so the
 * result survives JVM restarts and is visible to other instances. Lookups
 * ({@link #get(String)}) consult both the live map and the repository.
 *
 * <p>Old in-memory jobs are reaped automatically after {@link #JOB_TTL_MS};
 * persisted records remain in the database until explicitly purged.
 */
@Service
public class RenderJobService {

    public static final long JOB_TTL_MS = 30 * 60 * 1000L;     // 30 min
    public static final int  WORKERS    = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);

    private final ExecutorService executor = Executors.newFixedThreadPool(WORKERS, r -> {
        Thread t = new Thread(r, "render-job");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentMap<String, RenderJob> jobs = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    private final RenderJobRepository repository;

    @Autowired
    public RenderJobService(RenderJobRepository repository) {
        this.repository = repository;
    }

    /** Submit a new render job. The supplier is given a progress callback (0..1). */
    public RenderJob submit(String label, Function<RenderJob, RenderResult> work) {
        String id = "j-" + System.currentTimeMillis() + "-" + seq.incrementAndGet();
        RenderJob job = new RenderJob(id, label);
        jobs.put(id, job);
        String ownerId = currentOwnerOrNull();
        // Persist a QUEUED row up-front so the job is visible across instances even
        // before it starts executing.
        try {
            repository.save(new RenderJobEntity(id, label, ownerId));
        } catch (RuntimeException ignored) {
            // Persistence is best-effort; the in-memory entry is the source of truth
            // until the job reaches a terminal state.
        }
        executor.submit(() -> {
            try {
                RenderResult result = work.apply(job);
                job.complete(result);
                persistTerminal(job, ownerId);
            } catch (Throwable t) {
                job.fail(t);
                persistTerminal(job, ownerId);
            } finally {
                reapOldJobs();
            }
        });
        return job;
    }

    /**
     * Look up a job. First consults the live in-memory map; if absent (e.g. after a
     * JVM restart or because TTL has elapsed), falls back to the repository and
     * rehydrates a read-only {@link RenderJob} snapshot.
     */
    public RenderJob get(String id) {
        RenderJob live = jobs.get(id);
        if (live != null) return live;
        Optional<RenderJobEntity> persisted = repository.findById(id);
        return persisted.map(RenderJobService::rehydrate).orElse(null);
    }

    /** Returns the persisted PNG for a completed job, if any. */
    public Optional<byte[]> resultPng(String id) {
        return repository.findById(id).map(RenderJobEntity::getResultPng);
    }

    public void remove(String id) {
        jobs.remove(id);
        repository.deleteById(id);
    }

    private void reapOldJobs() {
        long cutoff = Instant.now().toEpochMilli() - JOB_TTL_MS;
        jobs.entrySet().removeIf(e -> e.getValue().createdAtEpochMs() < cutoff);
    }

    private void persistTerminal(RenderJob job, String ownerId) {
        try {
            RenderJobEntity entity = repository.findById(job.id())
                    .orElseGet(() -> new RenderJobEntity(job.id(), job.label(), ownerId));
            entity.setOwnerId(ownerId);
            entity.setProgress(job.progress());
            entity.setMessage(job.message());
            entity.setFinishedAt(Instant.now());
            if (job.state() == RenderJob.State.COMPLETED) {
                entity.setState(RenderJobEntity.State.COMPLETED);
                RenderResult r = job.result();
                if (r != null) {
                    double dpi = 25.4 / r.pixelSizeMm();
                    entity.setResultPng(PngExporter.toPngBytes(r, dpi));
                    entity.setResultPixelSizeMm(r.pixelSizeMm());
                    entity.setResultWidthPx(r.widthPx());
                    entity.setResultHeightPx(r.heightPx());
                }
            } else {
                entity.setState(RenderJobEntity.State.FAILED);
                Throwable t = job.error();
                if (t != null) {
                    String msg = t.getMessage();
                    entity.setErrorMessage(msg == null ? t.getClass().getSimpleName()
                            : msg.substring(0, Math.min(2048, msg.length())));
                }
            }
            repository.save(entity);
        } catch (IOException | RuntimeException e) {
            // Persistence failure must not crash the worker; the in-memory job
            // already holds the result for any active SSE subscribers.
        }
    }

    private static RenderJob rehydrate(RenderJobEntity e) {
        RenderJob job = new RenderJob(e.getId(), e.getLabel());
        switch (e.getState()) {
            case COMPLETED -> job.markCompletedExternally(e.getProgress(), e.getMessage());
            case FAILED -> job.markFailedExternally(e.getMessage(), e.getErrorMessage());
            case RUNNING -> job.reportProgress(e.getProgress(), e.getMessage());
            case QUEUED -> { /* nothing */ }
        }
        return job;
    }

    private static String currentOwnerOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        String name = auth.getName();
        return (name == null || "anonymousUser".equals(name)) ? null : name;
    }

    /**
     * Stop accepting new work and tear down the worker pool when the Spring
     * context closes (graceful shutdown / redeploy / test teardown). Without
     * this, the executor would keep its threads alive until JVM exit even after
     * the application context is destroyed.
     */
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
