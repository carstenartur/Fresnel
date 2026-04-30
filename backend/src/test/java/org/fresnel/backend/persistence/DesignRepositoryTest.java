package org.fresnel.backend.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository test for {@link DesignRepository} on the default H2 datasource
 * (Postgres-compatibility mode). Verifies CRUD + the per-owner / global listings.
 *
 * <p>Uses {@link SpringBootTest} rather than {@code @DataJpaTest} because the
 * Spring Boot 4 testing artifacts in this project don't ship the JPA test slice
 * — bringing up the full context is fast enough for these small assertions.
 * {@link Transactional} ensures each test rolls back its writes.
 */
@SpringBootTest
@Transactional
class DesignRepositoryTest {

    @Autowired DesignRepository repository;

    @Test
    void savesAndLoadsDesignWithJsonPayload() {
        DesignEntity entity = new DesignEntity(
                null, "single", 1, "my-zp", "alice",
                "{\"apertureDiameterMm\":4.0,\"focalLengthMm\":50.0}");
        DesignEntity saved = repository.save(entity);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        DesignEntity loaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getKind()).isEqualTo("single");
        assertThat(loaded.getOwnerId()).isEqualTo("alice");
        assertThat(loaded.getPayloadJson()).contains("apertureDiameterMm");
    }

    @Test
    void scopesListingsByOwner() {
        repository.save(new DesignEntity(null, "single", 1, "a1", "alice", "{\"x\":1}"));
        repository.save(new DesignEntity(null, "single", 1, "a2", "alice", "{\"x\":2}"));
        repository.save(new DesignEntity(null, "single", 1, "b1", "bob",   "{\"x\":3}"));

        List<DesignEntity> aliceDesigns = repository.findAllByOwnerIdOrderByCreatedAtDesc("alice");
        List<DesignEntity> bobDesigns   = repository.findAllByOwnerIdOrderByCreatedAtDesc("bob");

        assertThat(aliceDesigns).allMatch(e -> "alice".equals(e.getOwnerId())).hasSize(2);
        assertThat(bobDesigns).allMatch(e -> "bob".equals(e.getOwnerId())).hasSize(1);
    }
}
