package org.log2code.api.config;

import java.io.IOException;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the web build and falls back to {@code index.html} for client-side routes (T32 step 2), so that
 * {@code /logs/{id}} also works when it is opened directly (e.g. from the Dashboards link).
 *
 * <p>No fallback for {@code api/**} and {@code actuator/**} (an unknown API path stays a 404), nor for paths whose
 * last segment has a file extension (a missing {@code /assets/x.js} must be a 404, not HTML with status 200).
 */
class SpaResourceResolver extends PathResourceResolver {

    static final String INDEX = "index.html";

    @Override
    protected Resource getResource(String resourcePath, Resource location) throws IOException {
        Resource resource = super.getResource(resourcePath, location);
        if (resource != null || !isClientRoute(resourcePath)) {
            return resource;
        }
        return super.getResource(INDEX, location);
    }

    /** {@code resourcePath} is relative to the handler pattern {@code /**}, without a leading slash. */
    static boolean isClientRoute(String resourcePath) {
        String path = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        if (isUnder(path, "api") || isUnder(path, "actuator")) {
            return false;
        }
        String lastSegment = path.substring(path.lastIndexOf('/') + 1);
        return !lastSegment.contains(".");
    }

    private static boolean isUnder(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
