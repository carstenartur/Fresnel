package org.fresnel.backend.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Forwards the application's concrete client-side routes to the bundled SPA.
 * Spring Boot serves {@code classpath:/static/index.html}, populated by the Maven
 * frontend build.
 *
 * <p>This mapping is deliberately explicit. A former catch-all mapping used a
 * regular expression only for the first path segment. Consequently requests such
 * as {@code /assets/index-abc123.js} matched the segment {@code assets} and were
 * forwarded to {@code index.html} instead of reaching Spring's static-resource
 * handler. Browsers then received HTML for JavaScript and CSS files and the
 * packaged application could not start.</p>
 *
 * <p>API routes and static resources are never SPA routes. Add a route here when
 * the React application gains another top-level client-side route.</p>
 */
@Controller
public class SpaController {

    @RequestMapping(value = {
            "/",
            "/plugins/{pluginId}",
            "/plugins/{pluginId}/",
            "/compare",
            "/compare/",
            "/assistant",
            "/assistant/"
    })
    public String forward() {
        return "forward:/index.html";
    }
}
