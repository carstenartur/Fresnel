package org.fresnel.backend.api;

import org.fresnel.backend.jobs.RenderJobService;
import org.fresnel.backend.persistence.RenderJobEntity;
import org.fresnel.backend.persistence.RenderJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RenderJobControllerTest {

    private static final String SINGLE_BODY = """
            {
              "apertureDiameterMm": 4.0,
              "focalLengthMm": 50.0,
              "wavelengthNm": 550.0,
              "dpi": 300.0
            }
            """;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RenderJobRepository repository;

    @BeforeEach
    void clearPersistedFixtures() {
        repository.deleteAll();
    }

    @Test
    void submitPollAndDownloadRemainAvailableToTheOwner() throws Exception {
        String jobId = submitAs("alice");
        assertNotNull(jobId);
        assertThat(jobId).matches("^j-[A-Za-z0-9_-]{32}$");
        byte[] entropy = Base64.getUrlDecoder().decode(jobId.substring(2));
        assertEquals(RenderJobService.JOB_ID_ENTROPY_BYTES, entropy.length);

        String state = "QUEUED";
        long until = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < until) {
            MvcResult response = mvc.perform(get("/api/jobs/{id}", jobId)
                            .with(user("alice").roles("USER")))
                    .andExpect(status().isOk())
                    .andReturn();
            state = json.readTree(response.getResponse().getContentAsString())
                    .get("state").asText();
            if ("COMPLETED".equals(state) || "FAILED".equals(state)) break;
            Thread.sleep(100);
        }
        assertEquals("COMPLETED", state);

        mvc.perform(get("/api/jobs/{id}/result.png", jobId)
                        .with(user("alice").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void anonymousAndOtherUsersCannotReadALiveJob() throws Exception {
        String jobId = submitAs("alice");

        mvc.perform(get("/api/jobs/{id}", jobId))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/jobs/{id}/events", jobId))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/jobs/{id}/result.png", jobId))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/jobs/{id}", jobId)
                        .with(user("bob").roles("USER")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/jobs/{id}/events", jobId)
                        .with(user("bob").roles("USER")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/jobs/{id}/result.png", jobId)
                        .with(user("bob").roles("USER")))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/jobs/{id}", jobId)
                        .with(user("admin").roles("USER", "ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void persistedJobsApplyTheSameOwnerOrAdminPolicy() throws Exception {
        String jobId = fixtureId((byte) 0x41);
        RenderJobEntity entity = new RenderJobEntity(jobId, "single", "alice");
        entity.setState(RenderJobEntity.State.COMPLETED);
        entity.setProgress(1.0);
        entity.setMessage("completed");
        entity.setResultPng(new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        entity.setResultPixelSizeMm(0.1);
        entity.setResultWidthPx(1);
        entity.setResultHeightPx(1);
        entity.setFinishedAt(Instant.now());
        repository.saveAndFlush(entity);

        mvc.perform(get("/api/jobs/{id}", jobId)
                        .with(user("bob").roles("USER")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/jobs/{id}/result.png", jobId)
                        .with(user("bob").roles("USER")))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/jobs/{id}", jobId)
                        .with(user("alice").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"));
        mvc.perform(get("/api/jobs/{id}/result.png", jobId)
                        .with(user("alice").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
        mvc.perform(get("/api/jobs/{id}", jobId)
                        .with(user("admin").roles("USER", "ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void persistedInternalFailureDetailsAreNotReturnedToClients() throws Exception {
        String jobId = fixtureId((byte) 0x42);
        RenderJobEntity entity = new RenderJobEntity(jobId, "single", "alice");
        entity.setState(RenderJobEntity.State.FAILED);
        entity.setProgress(0.25);
        entity.setMessage("failed");
        entity.setErrorMessage("internal diagnostic details must remain private");
        entity.setFinishedAt(Instant.now());
        repository.saveAndFlush(entity);

        MvcResult result = mvc.perform(get("/api/jobs/{id}", jobId)
                        .with(user("alice").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("render failed"))
                .andExpect(jsonPath("$.error").value("render failed"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("internal diagnostic details");
    }

    @Test
    void unknownAndUnauthorizedIdentifiersAreExternallyIndistinguishable() throws Exception {
        String unknown = fixtureId((byte) 0x7f);
        mvc.perform(get("/api/jobs/{id}", unknown)
                        .with(user("alice").roles("USER")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/jobs/{id}/result.png", unknown)
                        .with(user("alice").roles("USER")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/jobs/{id}/events", unknown)
                        .with(user("alice").roles("USER")))
                .andExpect(status().isNotFound());
    }

    private String submitAs(String username) throws Exception {
        MvcResult response = mvc.perform(post("/api/jobs/single")
                        .with(user(username).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SINGLE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").exists())
                .andReturn();
        JsonNode root = json.readTree(response.getResponse().getContentAsString());
        return root.get("jobId").asText();
    }

    private static String fixtureId(byte value) {
        byte[] entropy = new byte[RenderJobService.JOB_ID_ENTROPY_BYTES];
        Arrays.fill(entropy, value);
        return "j-" + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }
}
