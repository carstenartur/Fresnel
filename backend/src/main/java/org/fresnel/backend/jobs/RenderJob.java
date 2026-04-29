package org.fresnel.backend.jobs;

import org.fresnel.optics.RenderResult;

import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A long-running render job. Threadsafe; a single producer reports progress and
 * eventually completes or fails, while many consumers (SSE subscribers) read state.
 *
 * <p>Listeners are notified on every progress update and on terminal state changes.
 */
public final class RenderJob {

    /** Job lifecycle. */
    public enum State { QUEUED, RUNNING, COMPLETED, FAILED }

    private final String id;
    private final String label;
    private final long createdAtEpochMs;
    private final AtomicReference<State> state = new AtomicReference<>(State.QUEUED);
    private volatile double progress = 0.0;
    private volatile String message = "queued";
    private volatile RenderResult result;
    private volatile Throwable error;

    private final CopyOnWriteArrayList<Consumer<RenderJob>> listeners = new CopyOnWriteArrayList<>();

    public RenderJob(String id, String label) {
        this.id = id;
        this.label = label;
        this.createdAtEpochMs = Instant.now().toEpochMilli();
    }

    public String id() { return id; }
    public String label() { return label; }
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
        this.message = "failed: " + t.getMessage();
        state.set(State.FAILED);
        notifyListeners();
    }

    public void addListener(Consumer<RenderJob> listener) { listeners.add(listener); }
    public void removeListener(Consumer<RenderJob> listener) { listeners.remove(listener); }

    public boolean isTerminal() {
        State s = state.get();
        return s == State.COMPLETED || s == State.FAILED;
    }

    private void notifyListeners() {
        for (Consumer<RenderJob> l : listeners) {
            try { l.accept(this); } catch (RuntimeException ignored) { /* never let a listener crash the producer */ }
        }
    }
}
