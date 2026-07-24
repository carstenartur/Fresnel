package org.fresnel.backend.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class RenderJobRepositoryTest {

    @Autowired RenderJobRepository repository;

    @Test
    void persistsTerminalRenderJobWithOwnerAndBlob() {
        RenderJobEntity entity = completed("j-1", "alice", Instant.now());
        entity.setResultPng(new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        entity.setResultPixelSizeMm(0.01);
        entity.setResultWidthPx(800);
        entity.setResultHeightPx(800);
        repository.saveAndFlush(entity);

        RenderJobEntity loaded = repository.findById("j-1").orElseThrow();
        assertThat(loaded.getState()).isEqualTo(RenderJobEntity.State.COMPLETED);
        assertThat(loaded.getOwnerId()).isEqualTo("alice");
        assertThat(loaded.getResultPng()).hasSize(4);
        assertThat(loaded.getResultWidthPx()).isEqualTo(800);
    }

    @Test
    void retentionCleanupDeletesOnlyFinishedJobsBeforeTheCutoff() {
        Instant now = Instant.now();
        repository.saveAndFlush(completed("j-old", "alice", now.minusSeconds(90 * 24 * 3600L)));
        repository.saveAndFlush(completed("j-current", "alice", now));
        repository.saveAndFlush(new RenderJobEntity("j-queued", "single", "alice"));

        long deleted = repository.deleteByFinishedAtBefore(now.minusSeconds(30 * 24 * 3600L));
        repository.flush();

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.findById("j-old")).isEmpty();
        assertThat(repository.findById("j-current")).isPresent();
        assertThat(repository.findById("j-queued")).isPresent();
    }

    private static RenderJobEntity completed(String id, String owner, Instant finishedAt) {
        RenderJobEntity entity = new RenderJobEntity(id, "single", owner);
        entity.setState(RenderJobEntity.State.COMPLETED);
        entity.setProgress(1.0);
        entity.setMessage("completed");
        entity.setFinishedAt(finishedAt);
        return entity;
    }
}
