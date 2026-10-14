package org.involens_be.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class OpenApiConfig {

    public static final String BEARER_AUTH_SCHEME = "bearerAuth";

    /**
     * Defines the public Involens API metadata and JWT Bearer authentication scheme used by Swagger UI.
     *
     * @return configured OpenAPI document model
     */
    @Bean
    public OpenAPI involensOpenApi() {
        log.info("Configuring Involens OpenAPI documentation");
        OpenAPI openApi = new OpenAPI()
                .info(new Info()
                        .title("Involens API")
                        .version("v1")
                        .description("API documentation for the Involens backend"))
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH_SCHEME, new SecurityScheme()
                                .name(BEARER_AUTH_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
        log.info("Involens OpenAPI documentation configured successfully");
        return openApi;
    }
}
