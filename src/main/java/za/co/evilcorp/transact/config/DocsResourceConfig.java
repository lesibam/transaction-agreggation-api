package za.co.evilcorp.transact.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the API docs viewer (ADR-014) at /docs/** - a custom index.html plus
 * the build-copied openapi.yaml (see pom.xml resources) - and the swagger-ui
 * distribution's JS/CSS at /docs-assets/**, deliberately NOT via Boot's
 * automatic /webjars/** mapping (off - see application.yml), so
 * app.docs.enabled actually controls reachability of both. Independent of
 * UiResourceConfig/app.ui.enabled: a deployment may want API docs reachable
 * for integrators with the internal dashboard switched off, or vice versa.
 *
 * /docs-assets/** forwards to the webjar root unversioned - the version
 * segment (e.g. 5.32.15) is part of the requested path, resolved by
 * webapp/docs/index.html, which must stay in sync with pom.xml's
 * swagger-ui.version property. A version bump that misses index.html fails
 * loudly (404 on the JS/CSS, caught by DocsAndUiAvailabilityTest), not
 * silently.
 */
@Configuration
@ConditionalOnProperty(name = "app.docs.enabled", havingValue = "true", matchIfMissing = true)
public class DocsResourceConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/docs/**")
                .addResourceLocations("classpath:/webapp/docs/");
        registry.addResourceHandler("/docs-assets/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/swagger-ui/");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Same reasoning as UiResourceConfig: forward the two paths a
        // visitor actually types to the directory's index.html.
        registry.addViewController("/docs").setViewName("forward:/docs/index.html");
        registry.addViewController("/docs/").setViewName("forward:/docs/index.html");
    }
}
