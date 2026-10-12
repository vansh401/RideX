package com.ridex.ridex_app.config;

import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ridex.ridex_app.common.ApiError;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Describes the API for Swagger UI (http://localhost:8080/swagger-ui.html) and
 * /v3/api-docs. The JSON is generated from the controllers and validation annotations, so it
 * cannot drift from the code.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";
    private static final String ERROR_SCHEMA = "ApiError";

    @Bean
    public OpenAPI rideXOpenApi() {
        OpenAPI api = new OpenAPI()
                .info(new Info()
                        .title("RideX API")
                        .version("v1")
                        .description("""
                                Ride-hailing backend.

                                Authenticate with POST /api/auth/login, then press "Authorize" and paste \
                                the accessToken. Every error uses the ApiError shape. Lists are paginated \
                                with page (0-based) and size (max 100).""")
                )
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));

        // Register the shared error model once so every operation can point at it
        ResolvedSchema error = ModelConverters.getInstance()
                .resolveAsResolvedSchema(new AnnotatedType(ApiError.class));
        api.getComponents().addSchemas(ERROR_SCHEMA, error.schema);
        if (error.referencedSchemas != null) {
            error.referencedSchemas.forEach(api.getComponents()::addSchemas);
        }
        return api;
    }

    /** Adds the error responses every endpoint can produce, unless the endpoint documents its own. */
    @Bean
    public OperationCustomizer standardErrorResponses() {
        return (Operation operation, org.springframework.web.method.HandlerMethod handlerMethod) -> {
            ApiResponses responses = operation.getResponses();
            if (responses == null) {
                responses = new ApiResponses();
                operation.setResponses(responses);
            }
            addIfAbsent(responses, "400", "Validation failed or malformed request");
            addIfAbsent(responses, "401", "Missing, invalid or expired credentials");
            addIfAbsent(responses, "403", "Authenticated but not allowed");
            addIfAbsent(responses, "409", "Conflicts with existing data or current state");
            addIfAbsent(responses, "500", "Unexpected server error");
            return operation;
        };
    }

    private static void addIfAbsent(ApiResponses responses, String code, String description) {
        if (responses.containsKey(code)) {
            return;
        }
        Schema<?> ref = new Schema<>().$ref("#/components/schemas/" + ERROR_SCHEMA);
        responses.addApiResponse(code, new ApiResponse()
                .description(description)
                .content(new Content().addMediaType("application/json", new MediaType().schema(ref))));
    }
}
