# OpenAPI Spezifikation: K8s Auth Sidecar

Dieses Dokument enthält OpenAPI-Beispiele für die Endpunkte des Sidecars.

## `GET /authorize`

Dieser Endpunkt wird von Envoy (`ext_authz`) oder Nginx (`auth_request`) aufgerufen, um zu prüfen, ob ein eingehender Request autorisiert ist. Das Sidecar prüft das JWT und wertet die OPA-WASM Policy basierend auf dem `AuthContext` und dem originalen HTTP-Request aus.

### Parameter (via Header)

| Header | Typ | Beschreibung |
|--------|-----|--------------|
| `Authorization` | string | Das Bearer-Token des Users (wird von `SidecarRequestProcessor` validiert). |
| `X-Forwarded-Uri` | string | Der URl/Pfad des ursprünglichen Requests, der validiert werden soll (z. B. `/api/data`). |
| `X-Forwarded-Method` | string | Die HTTP-Methode des ursprünglichen Requests (z. B. `GET`, `POST`). |

### Response

- **200 OK**: Der Request ist autorisiert.
    - **Header**: `X-Auth-User`, `X-Auth-Roles`, etc. (werden vom Sidecar zur Anreicherung gesetzt).
- **401 Unauthorized**: Kein gültiges Token gefunden.
- **403 Forbidden**: Token gültig, aber keine Berechtigung für den originalen Pfad/die Methode durch die OPA-Policy.
- **500 Internal Server Error**: Fehler bei der Validierung oder Policy-Evaluierung.

### Beispiel Aufruf

```http
GET /authorize HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJhbGci...
X-Forwarded-Uri: /api/protected/resource
X-Forwarded-Method: POST
```

### OpenAPI (YAML Snippet)

```yaml
openapi: 3.0.0
info:
  title: K8s Auth Sidecar
  version: 1.0.0
paths:
  /authorize:
    get:
      summary: Überprüft die Autorisierung eines Requests
      description: Endpoint for Envoy ext_authz or Nginx auth_request to validate JWT and evaluate OPA policy for the target path.
      parameters:
        - in: header
          name: X-Forwarded-Uri
          schema:
            type: string
          required: false
          description: "Original target path requested by the client"
        - in: header
          name: X-Forwarded-Method
          schema:
            type: string
          required: false
          description: "Original HTTP method requested by the client"
      responses:
        '200':
          description: Authorization granted
        '401':
          description: Unauthorized (Token is missing or invalid)
        '403':
          description: Forbidden (Request denied by policy)
          content:
            application/json:
              schema:
                type: object
                properties:
                  error:
                    type: string
```
