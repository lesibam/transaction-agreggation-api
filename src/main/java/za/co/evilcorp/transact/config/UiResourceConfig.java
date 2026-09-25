package za.co.evilcorp.transact.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the read-only web dashboard (ADR-013) at /ui/** from a classpath
 * location Boot's own static-resource auto-configuration never touches
 * (spring.web.resources.add-mappings is off - see application.yml). Only
 * registered when app.ui.enabled=true, so disabling it means /ui/** has no
 * handler at all - a genuine 404 via GlobalExceptionHandler's
 * NoResourceFoundException mapping, not just a hidden link.
 */
@Configuration
@ConditionalOnProperty(name = "app.ui.enabled", havingValue = "true", matchIfMissing = true)
public class UiResourceConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/ui/**")
                .addResourceLocations("classpath:/webapp/ui/");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // ResourceHandlerRegistration doesn't resolve a directory request to
        // its index.html the way Boot's root-path "welcome page" feature
        // does - forward the two paths a visitor actually types.
        registry.addViewController("/ui").setViewName("forward:/ui/index.html");
        registry.addViewController("/ui/").setViewName("forward:/ui/index.html");
    }
}
