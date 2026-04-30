package org.fresnel.backend.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Persisted snapshot of a {@link org.fresnel.backend.jobs.RenderJob}.
 *
 * <p>Live progress for in-flight jobs is tracked in memory by
 * {@link org.fresnel.backend.jobs.RenderJobService}; this entity is written when a
 * job transitions to a terminal state ({@code COMPLETED} / {@code FAILED}) so the
 * result survives JVM restarts and is visible to other instances.
 */
@Entity
@Table(name = "render_jobs", indexes = {
        @Index(name = "idx_render_jobs_owner", columnList = "owner_id"),
        @Index(name = "idx_render_jobs_created_at", columnList = "created_at")
})
public class RenderJobEntity {

    public enum State { QUEUED, RUNNING, COMPLETED, FAILED }

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 64)
    private String id;

    @Column(name = "label", nullable = false, length = 64)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private State state;

    @Column(name = "progress", nullable = false)
    private double progress;

    @Column(name = "message", length = 1024)
    private String message;

    @Column(name = "error_message", length = 2048)
    private String errorMessage;

    @Column(name = "owner_id", length = 128)
    private String ownerId;

    @Lob
    @Column(name = "result_png")
    private byte[] resultPng;

    @Column(name = "result_pixel_size_mm")
    private Double resultPixelSizeMm;

    @Column(name = "result_width_px")
    private Integer resultWidthPx;

    @Column(name = "result_height_px")
    private Integer resultHeightPx;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public RenderJobEntity() {}

    public RenderJobEntity(String id, String label, String ownerId) {
        this.id = id;
        this.label = label;
        this.ownerId = ownerId;
        this.state = State.QUEUED;
        this.progress = 0.0;
        this.message = "queued";
    }

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = Instant.now(); }

    public String getId() { return id; }
    public String getLabel() { return label; }
    public State getState() { return state; }
    public void setState(State state) { this.state = state; }
    public double getProgress() { return progress; }
    public void setProgress(double progress) { this.progress = progress; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String e) { this.errorMessage = e; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public byte[] getResultPng() { return resultPng; }
    public void setResultPng(byte[] resultPng) { this.resultPng = resultPng; }
    public Double getResultPixelSizeMm() { return resultPixelSizeMm; }
    public void setResultPixelSizeMm(Double v) { this.resultPixelSizeMm = v; }
    public Integer getResultWidthPx() { return resultWidthPx; }
    public void setResultWidthPx(Integer v) { this.resultWidthPx = v; }
    public Integer getResultHeightPx() { return resultHeightPx; }
    public void setResultHeightPx(Integer v) { this.resultHeightPx = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
