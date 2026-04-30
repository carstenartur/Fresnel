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
    void persistsTerminalRenderJobWithBlob() {
        RenderJobEntity entity = new RenderJobEntity("j-1", "single", "alice");
        entity.setState(RenderJobEntity.State.COMPLETED);
        entity.setProgress(1.0);
        entity.setMessage("done");
        entity.setResultPng(new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        entity.setResultPixelSizeMm(0.01);
        entity.setResultWidthPx(800);
        entity.setResultHeightPx(800);
        entity.setFinishedAt(Instant.now());
        repository.save(entity);

        RenderJobEntity loaded = repository.findById("j-1").orElseThrow();
        assertThat(loaded.getState()).isEqualTo(RenderJobEntity.State.COMPLETED);
        assertThat(loaded.getOwnerId()).isEqualTo("alice");
        assertThat(loaded.getResultPng()).hasSize(4);
        assertThat(loaded.getResultWidthPx()).isEqualTo(800);
    }
}
