package org.log2code.api.config;

import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the dev web app (T23 step 5; {@code web} runs on Vite's default port) and serving of the web build
 * with the SPA fallback (T32 step 2).
 *
 * <p>Boot's own static mapping is switched off ({@code spring.web.resources.add-mappings=false}), so {@code /**}
 * is registered only here, still from {@code spring.web.resources.static-locations}
 * ({@code file:/app/static/,classpath:/static/} in the Docker image).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final WebProperties webProperties;

    public WebConfig(WebProperties webProperties) {
        this.webProperties = webProperties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins("http://localhost:5173")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
            .addResourceLocations(webProperties.getResources().getStaticLocations())
            .resourceChain(true)
            .addResolver(new SpaResourceResolver());
    }
}
