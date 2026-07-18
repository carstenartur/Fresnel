package org.fresnel.backend.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class FresnelJobProductionControllerTest {

    private static final MediaType FRESNEL_JOB =
            MediaType.parseMediaType(FresnelJobDocument.MEDIA_TYPE);

    @Autowired MockMvc mvc;

    @Test
    void executesTheOutputDeclaredByTheCheckedInDocumentationJob() throws Exception {
        MvcResult result = mvc.perform(post(
                        "/api/designs/job/execute/documentation-preview")
                        .contentType(FRESNEL_JOB)
                        .accept(MediaType.IMAGE_PNG)
                        .content(Files.readAllBytes(repositoryFile(
                                "docs/jobs/zone-plate/on-axis.fresnel"))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG))
                .andExpect(header().string(
                        "Content-Disposition", containsString("on-axis.png")))
                .andExpect(header().string(
                        "X-Fresnel-Normalized-SHA256", matchesPattern("[0-9a-f]{64}")))
                .andReturn();

        assertTrue(result.getResponse().getContentAsByteArray().length > 100);
    }

    @Test
    void rejectsAnOutputThatTheJobDidNotDeclare() throws Exception {
        mvc.perform(post("/api/designs/job/execute/not-declared")
                        .contentType(FRESNEL_JOB)
                        .content(Files.readAllBytes(repositoryFile(
                                "docs/jobs/zone-plate/on-axis.fresnel"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsAJobWithoutAProductionPlan() throws Exception {
        String job = Files.readString(repositoryFile(
                "docs/jobs/zone-plate/on-axis.fresnel"));
        int productionStart = job.indexOf("  \"production\":");
        int provenanceStart = job.indexOf("  \"provenance\":");
        String withoutProduction = job.substring(0, productionStart)
                + job.substring(provenanceStart);

        mvc.perform(post("/api/designs/job/execute/documentation-preview")
                        .contentType(FRESNEL_JOB)
                        .content(withoutProduction))
                .andExpect(status().isNotFound());
    }

    private static Path repositoryFile(String relativePath) {
        for (Path candidate : List.of(
                Path.of(relativePath),
                Path.of("..").resolve(relativePath))) {
            if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize();
        }
        throw new IllegalStateException("Could not locate repository file: " + relativePath);
    }
}
