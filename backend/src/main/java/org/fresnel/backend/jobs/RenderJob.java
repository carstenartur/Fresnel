package org.fresnel.backend.jobs;

import org.fresnel.optics.RenderResult;

import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A long-running render job. Threadsafe; a single producer reports progress and
 * eventually completes or fails, while many authorized consumers may read state.
 *
 * <p>The immutable owner identifier travels with the live object as well as its
 * persisted entity. This prevents the in-memory fast path from bypassing the
 * same owner-or-admin policy applied to rehydrated jobs.</p>
 */
public final class RenderJob {

    /** Job lifecycle. */
    public enum State { QUEUED, RUNNING, COMPLETED, FAILED }

    private final String id;
    private final String label;
    private final String ownerId;
    private final long createdAtEpochMs;
    private final AtomicReference<State> state = new AtomicReference<>(State.QUEUED);
    private volatile double progress = 0.0;
    private volatile String message = "queued";
    private volatile RenderResult result;
    private volatile Throwable error;

    private final CopyOnWriteArrayList<Consumer<RenderJob>> listeners = new CopyOnWriteArrayList<>();

    public RenderJob(String id, String label, String ownerId) {
        this.id = id;
        this.label = label;
        this.ownerId = ownerId;
        this.createdAtEpochMs = Instant.now().toEpochMilli();
    }

    public String id() { return id; }
    public String label() { return label; }
    public String ownerId() { return ownerId; }
    public long createdAtEpochMs() { return createdAtEpochMs; }
    public State state() { return state.get(); }
    public double progress() { return progress; }
    public String message() { return message; }
    public RenderResult result() { return result; }
    public Throwable error() { return error; }

    /** Called by the worker to update progress. Triggers listeners. */
    public void reportProgress(double frac, String msg) {
        if (state.get() == State.QUEUED) state.compareAndSet(State.QUEUED, State.RUNNING);
        this.progress = Math.max(0.0, Math.min(1.0, frac));
        if (msg != null) this.message = msg;
        notifyListeners();
    }

    void complete(RenderResult r) {
        this.result = r;
        this.progress = 1.0;
        this.message = "completed";
        state.set(State.COMPLETED);
        notifyListeners();
    }

    void fail(Throwable t) {
        this.error = t;
        // Keep the detailed exception only for server-side persistence/logging.
        // Client status responses deliberately expose a generic failure message.
        this.message = "failed";
        state.set(State.FAILED);
        notifyListeners();
    }

    public void addListener(Consumer<RenderJob> listener) { listeners.add(listener); }
    public void removeListener(Consumer<RenderJob> listener) { listeners.remove(listener); }

    public boolean isTerminal() {
        State s = state.get();
        return s == State.COMPLETED || s == State.FAILED;
    }

    /** Rehydrates a read-only completed snapshot from persistence. */
    public void markCompletedExternally(double progress, String message) {
        this.progress = Math.max(0.0, Math.min(1.0, progress));
        if (message != null) this.message = message;
        state.set(State.COMPLETED);
    }

    /** Counterpart of {@link #markCompletedExternally} for failed persisted jobs. */
    public void markFailedExternally(String message, String errorMessage) {
        this.message = "failed";
        if (errorMessage != null) this.error = new RuntimeException(errorMessage);
        state.set(State.FAILED);
    }

    private void notifyListeners() {
        for (Consumer<RenderJob> listener : listeners) {
            try {
                listener.accept(this);
            } catch (RuntimeException ignored) {
                // A disconnected listener must never crash the render producer.
            }
        }
    }
}
