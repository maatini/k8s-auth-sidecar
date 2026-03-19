package de.edeka.eit.sidecar.infrastructure.openapi;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.OASModelReader;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Components;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.Paths;
import org.eclipse.microprofile.openapi.models.info.Info;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.parameters.Parameter;
import org.eclipse.microprofile.openapi.models.responses.APIResponse;
import org.eclipse.microprofile.openapi.models.responses.APIResponses;
import org.eclipse.microprofile.openapi.models.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.models.security.SecurityScheme;

/**
 * Programmatic OpenAPI model for Swagger UI Bearer-Token support.
 * <p>
 * Quarkus SmallRye OpenAPI does not scan {@code @Route} reactive routes,
 * so we build the spec programmatically via {@link OASModelReader}.
 * </p>
 */
public class OpenApiConfig implements OASModelReader {

    private static final String SCHEME_NAME = "bearerAuth";

    @Override
    public OpenAPI buildModel() {
        return OASFactory.createOpenAPI()
                .info(buildInfo())
                .components(buildComponents())
                .paths(buildPaths());
    }

    private Info buildInfo() {
        return OASFactory.createInfo()
                .title("K8s Auth Sidecar API")
                .version("0.3.0")
                .description(
                        "Envoy ext_authz sidecar for JWT validation "
                                + "and OPA authorization");
    }

    private Components buildComponents() {
        SecurityScheme scheme = OASFactory.createSecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT");

        return OASFactory.createComponents()
                .addSecurityScheme(SCHEME_NAME, scheme);
    }

    private Paths buildPaths() {
        return OASFactory.createPaths()
                .addPathItem("/authorize", buildAuthorizePath())
                .addPathItem("/userinfo", buildUserInfoPath());
    }

    private PathItem buildAuthorizePath() {
        Operation op = OASFactory.createOperation()
                .summary("Authorize request")
                .description(
                        "Envoy ext_authz endpoint that validates the token "
                                + "and queries OPA policies")
                .operationId("authorize")
                .addParameter(headerParameter("Authorization", "Bearer Token (fallback if Authorize-Button fails)", false))
                .addParameter(headerParameter("X-Envoy-Original-Path", "Original requested path (simulated, e.g. /api/orders)", false))
                .addParameter(headerParameter("X-Forwarded-Method", "Original requested method (simulated, e.g. POST)", false))
                .addSecurityRequirement(bearerRequirement())
                .responses(authorizeResponses());

        return OASFactory.createPathItem().GET(op);
    }

    private PathItem buildUserInfoPath() {
        Operation op = OASFactory.createOperation()
                .summary("Get user info")
                .description(
                        "Extracts user context, roles and permissions "
                                + "from token and an optional RolesService")
                .operationId("userinfo")
                .addParameter(headerParameter("Authorization", "Bearer Token", false))
                .addSecurityRequirement(bearerRequirement())
                .responses(userInfoResponses());

        return OASFactory.createPathItem().GET(op);
    }

    private Parameter headerParameter(String name, String description, boolean required) {
        Schema stringSchema = OASFactory.createSchema().type(java.util.List.of(Schema.SchemaType.STRING));
        return OASFactory.createParameter()
                .in(Parameter.In.HEADER)
                .name(name)
                .description(description)
                .required(required)
                .schema(stringSchema);
    }

    private SecurityRequirement bearerRequirement() {
        return OASFactory.createSecurityRequirement()
                .addScheme(SCHEME_NAME);
    }

    private APIResponses authorizeResponses() {
        return OASFactory.createAPIResponses()
                .addAPIResponse("200", response("Authorized"))
                .addAPIResponse("401",
                        response("Unauthorized - invalid token"))
                .addAPIResponse("403",
                        response("Forbidden - denied by OPA"));
    }

    private APIResponses userInfoResponses() {
        return OASFactory.createAPIResponses()
                .addAPIResponse("200",
                        response("User info retrieved successfully"))
                .addAPIResponse("401",
                        response("Unauthorized - invalid token"))
                .addAPIResponse("403",
                        response("Forbidden - denied by OPA"));
    }

    private APIResponse response(String description) {
        return OASFactory.createAPIResponse()
                .description(description);
    }
}
