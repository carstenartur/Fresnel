package org.fresnel.backend.jobs;

import org.fresnel.optics.RenderResult;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * In-memory render-job registry with progress reporting via {@link RenderJob}.
 *
 * <p>This intentionally avoids Spring Batch / Postgres / Redis; for the planned
 * workloads (single-user design tool, jobs that complete in seconds to a couple of
 * minutes) an in-memory map plus a fixed-size {@link ExecutorService} is the
 * appropriate complexity. Progress is exposed to clients via Server-Sent Events.
 *
 * <p>Old jobs are reaped automatically after {@link #JOB_TTL_MS}.
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

    /** Submit a new render job. The supplier is given a progress callback (0..1). */
    public RenderJob submit(String label, Function<RenderJob, RenderResult> work) {
        String id = "j-" + System.currentTimeMillis() + "-" + seq.incrementAndGet();
        RenderJob job = new RenderJob(id, label);
        jobs.put(id, job);
        executor.submit(() -> {
            try {
                RenderResult result = work.apply(job);
                job.complete(result);
            } catch (Throwable t) {
                job.fail(t);
            } finally {
                reapOldJobs();
            }
        });
        return job;
    }

    public RenderJob get(String id) { return jobs.get(id); }

    public void remove(String id) { jobs.remove(id); }

    private void reapOldJobs() {
        long cutoff = Instant.now().toEpochMilli() - JOB_TTL_MS;
        jobs.entrySet().removeIf(e -> e.getValue().createdAtEpochMs() < cutoff);
    }
}
