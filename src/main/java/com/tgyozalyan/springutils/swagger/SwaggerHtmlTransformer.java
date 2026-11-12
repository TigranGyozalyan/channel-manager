package com.tgyozalyan.springutils.swagger;

import jakarta.servlet.http.HttpServletRequest;
import org.springdoc.core.utils.Constants;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.resource.ResourceTransformer;
import org.springframework.web.servlet.resource.ResourceTransformerChain;
import org.springframework.web.servlet.resource.TransformedResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Swagger has hardcoded URL to its pet project inside the webjars dependency. Their own springdoc starter-ui does
 * a lot of replaces and additional HTML code insertion. Since that masterpiece-of-dependency didn't work for some
 * reason, we had to implement our own. Seems like we need only the replacement of the URL, the rest isn't relevant?
 * The resource server controller also interferes with the existing controller code as the resource location is hard mapped to <code>/</code> without
 * any configuration knobs to override the mapping.
 */
class SwaggerHtmlTransformer implements ResourceTransformer {

    @Override
    public Resource transform(HttpServletRequest request, Resource resource,
                              ResourceTransformerChain transformerChain) throws IOException {
        String html = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new TransformedResource(resource,
                html.replace(Constants.SWAGGER_UI_DEFAULT_URL, "/v3/api-docs").getBytes(StandardCharsets.UTF_8)
        );
    }
}
