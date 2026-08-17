package api.m2.file.configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        final String securitySchemeName = "bearerAuth";

        return new OpenAPI()
                .addSecurityItem(new SecurityRequirement()
                        .addList(securitySchemeName))
                .components(new Components()
                        .addSecuritySchemes(securitySchemeName,
                                new SecurityScheme()
                                        .name(securitySchemeName)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")))
                .info(new Info()
                        .title("api-keep API")
                        .description("""
                                Árbol de archivos y carpetas por workspace, sobre disco local.

                                **Funcionalidades:**
                                • Subida, descarga y organización de archivos/carpetas por workspace
                                • Descarga de carpetas como zip on-the-fly
                                • Dedup de subidas por checksum SHA-256
                                • Papelera con purga automática (soft-delete, 1 día de retención)
                                • Compartir archivos app-a-app (READ / WRITE / READ_WRITE)
                                • Actualizaciones en vivo por WebSocket (STOMP)

                                **Autenticación:** JWT Bearer Token (OAuth2, Keycloak realm `m2`)
                                """)
                        .version("1.2.1")
                        .contact(new Contact()
                                .name("API Support")
                                .email("api-support@movement.eva-core.com")));
    }
}
