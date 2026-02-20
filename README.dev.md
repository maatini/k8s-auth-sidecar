# Dev-Setup in 60 Seconds

Accelerate your local development with this pre-configured magical dev environment. Everything you need is set up right out of the box using Quarkus `%dev` profile and WireMock.

## Prerequisites
- Docker & Docker Compose
- JDK 21+ & Maven

## Step-by-Step Guide

1. **Clone the repository and enter the directory**
   ```bash
   git clone https://github.com/maatini/k8s-auth-sidecar.git
   cd k8s-auth-sidecar
   ```

2. **Start the Mock Infrastructure (WireMock)**
   This starts a local OIDC Server (Port 8090) and a local Roles Service (Port 8089).
   ```bash
   docker compose -f docker-compose.dev.yml up -d
   ```

3. **Start the Sidecar in Dev Mode**
   ```bash
   mvn compile quarkus:dev
   ```
   The application automatically uses the `%dev` profile, which sets:
    - HTTP port to `8080`
    - OIDC issuer to the local WireMock OIDC server (`http://localhost:8090/realms/master`)
    - Roles service client to the local WireMock Roles server (`http://localhost:8089`)
    - Caching disabled for seamless testing.

## Getting a Test JWT

We have pre-configured WireMock to return a valid, signed JWT for testing. Simply call the mock token endpoint:

```bash
curl -X POST http://localhost:8090/realms/master/protocol/openid-connect/token | jq -r .access_token
```

You can then pass this token to the sidecar:
```bash
export TOKEN=$(curl -s -X POST http://localhost:8090/realms/master/protocol/openid-connect/token | jq -r .access_token)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/something
```
*(The pre-configured token belongs to `test-user-123` with email `test.user@example.com`)*

## Customizing Mocks

All mock responses are defined as JSON files in the `wiremock/` directory. If you change a mapping, WireMock typically picks it up automatically or you can restart the containers.

### Adding New Role Mappings
To test a different user or change permissions, simply edit or copy the mappings in `wiremock/roles/mappings/`. For example, `roles.json` matches URLs like `/api/v1/users/{userId}/roles`. Thanks to WireMock response templating, the mock automatically injects the requested `{userId}` into the response. 

## Mock Mode / In-Memory Fallback
If you don't want to use WireMock for the Roles Service, you can create a lightweight, in-memory mock directly in Quarkus.

Create the following class in `src/main/java/space/maatini/sidecar/client/InMemoryRolesService.java`:

```java
package space.maatini.sidecar.client;

import io.quarkus.arc.profile.IfBuildProfile;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.annotation.Priority;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import space.maatini.sidecar.model.RolesResponse;

import java.util.Set;

/**
 * Lightweight, in-memory alternatives to the WireMock Roles server.
 * Only activated in the "dev" profile.
 */
@Alternative
@Priority(1)
@ApplicationScoped
@RestClient
@IfBuildProfile("dev")
public class InMemoryRolesService implements RolesServiceClient {

    @Override
    public Uni<RolesResponse> getRoles(String userId) {
        return Uni.createFrom().item(
            new RolesResponse(userId, Set.of("developer"), Set.of("read:all"), "tenant-A")
        );
    }

    @Override
    public Uni<RolesResponse> getRolesForTenant(String userId, String tenantId) {
        return getRoles(userId);
    }

    @Override
    public Uni<RolesResponse> getPermissions(String userId) {
        return getRoles(userId);
    }

    @Override
    public Uni<RolesResponse> getAuthorization(String userId) {
        return getRoles(userId);
    }
}
```
This is an excellent option for unit testing or when running Docker Compose is not practical.
