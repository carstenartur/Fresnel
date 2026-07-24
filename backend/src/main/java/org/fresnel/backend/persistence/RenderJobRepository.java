package org.fresnel.backend.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface RenderJobRepository extends JpaRepository<RenderJobEntity, String> {

    List<RenderJobEntity> findAllByOwnerIdOrderByCreatedAtDesc(String ownerId);

    List<RenderJobEntity> findAllByOrderByCreatedAtDesc();

    /** Delete terminal job records older than the configured retention window. */
    @Transactional
    long deleteByFinishedAtBefore(Instant cutoff);
}
