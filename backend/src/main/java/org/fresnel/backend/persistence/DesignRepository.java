package org.fresnel.backend.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DesignRepository extends JpaRepository<DesignEntity, UUID> {

    List<DesignEntity> findAllByOwnerIdOrderByCreatedAtDesc(String ownerId);

    List<DesignEntity> findAllByOrderByCreatedAtDesc();
}
