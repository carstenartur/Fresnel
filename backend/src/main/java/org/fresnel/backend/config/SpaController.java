package org.fresnel.backend.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Catch-all controller that forwards unknown (non-API) routes to the SPA's
 * {@code index.html}.  Spring Boot serves the file from
 * {@code classpath:/static/index.html} which is populated by the Maven
 * frontend build.
 *
 * <p>API routes ({@code /api/**}) and known static resources are matched by
 * earlier handler mappings and therefore never reach this controller.
 */
@Controller
public class SpaController {

    @RequestMapping(value = {
            "/",
            "/{path:[^\\.]*}",
            "/{path:[^\\.]*}/**"
    })
    public String forward() {
        return "forward:/index.html";
    }
}
