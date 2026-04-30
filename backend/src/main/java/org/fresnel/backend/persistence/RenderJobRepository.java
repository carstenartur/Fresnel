package org.fresnel.backend.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RenderJobRepository extends JpaRepository<RenderJobEntity, String> {

    List<RenderJobEntity> findAllByOwnerIdOrderByCreatedAtDesc(String ownerId);

    List<RenderJobEntity> findAllByOrderByCreatedAtDesc();
}
