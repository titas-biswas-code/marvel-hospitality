package com.marvel.hospitality.platform.security;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * Registers the Keycloak bearer scheme in the OpenAPI document so Swagger UI shows an "Authorize" button. Active only
 * when the service has springdoc on its classpath. A customizer, not an {@code OpenAPI} bean, so a service can still
 * describe its own API info.
 */
@AutoConfiguration
@ConditionalOnClass(OpenApiCustomizer.class)
public class OpenApiSecurityAutoConfiguration {

    static final String BEARER_AUTH = "bearerAuth";

    @Bean
    OpenApiCustomizer bearerAuthOpenApiCustomizer(@Value("${spring.application.name:}") String applicationName) {
        return openApi -> {
            if (openApi.getInfo() == null || "OpenAPI definition".equals(openApi.getInfo().getTitle())) {
                openApi.info(new Info().title(applicationName).version("v1"));
            }
            if (openApi.getComponents() == null) {
                openApi.components(new Components());
            }
            openApi.getComponents().addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("Keycloak access token for realm `marvel` (see docs/postman or `make token`)."));
            openApi.addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
        };
    }
}
