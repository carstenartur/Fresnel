package org.fresnel.backend.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted Fresnel design envelope.
 *
 * <p>Mirrors {@link org.fresnel.backend.api.DesignDocument} (kind / version / payload),
 * adds {@code id}, an optional human-friendly {@code name}, audit timestamps and an
 * optional {@code ownerId} (populated from the authenticated principal once Spring
 * Security is enabled — left nullable for now to keep anonymous save/load working).
 *
 * <p>The payload is stored as a JSON string. The Flyway-managed schema declares
 * {@code jsonb} on Postgres and {@code CLOB} on H2, both of which Hibernate writes
 * a String to without a vendor-specific {@code JsonType}.
 */
@Entity
@Table(name = "designs", indexes = {
        @Index(name = "idx_designs_owner", columnList = "owner_id"),
        @Index(name = "idx_designs_created_at", columnList = "created_at")
})
public class DesignEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "kind", nullable = false, length = 32)
    private String kind;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "name", length = 256)
    private String name;

    @Column(name = "owner_id", length = 128)
    private String ownerId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payloadJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public DesignEntity() {}

    public DesignEntity(UUID id, String kind, int schemaVersion, String name,
                        String ownerId, String payloadJson) {
        this.id = id;
        this.kind = kind;
        this.schemaVersion = schemaVersion;
        this.name = name;
        this.ownerId = ownerId;
        this.payloadJson = payloadJson;
    }

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int v) { this.schemaVersion = v; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String p) { this.payloadJson = p; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
