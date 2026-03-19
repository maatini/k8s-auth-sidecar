# gen-jwt.py

This utility script generates RS256 JWTs and JWKS for Quarkus OIDC and WireMock tests. It calculates the necessary RSA keys, creates a localized JWT based on YAML user data, and directly updates WireMock mappings to simplify local development and testing.

## Features

- **Key Generation:** Generates a new RSA 2048 key pair.
- **JWKS Assembly:** Creates a JSON Web Key Set matching the generated public key.
- **JWT Creation:** Constructs a JWT access token with custom user claims loaded from a simple YAML file.
- **Auto-Injection (WireMock):** Automatically updates `jwks.json` and `token.json` under `../wiremock/oidc/mappings/` with the active keys and claims. No manual copy-pasting is required!

## Prerequisites

The project specifies its environment requirements using `uv` (a fast Python package installer and resolver), defined in `pyproject.toml`.

Packages required:
- `pyjwt[crypto] >= 2.8`
- `pyyaml >= 6.0`

> **Note:** Make sure you have Python >= 3.12. If `uv` is not available through devbox, you can install the dependencies inside a standard Python virtual environment (`.venv`).

## Usage

You can run the script via `uv` or any standard Python instance that has the respective dependencies installed. All you need is a `.yaml` file detailing the specific OIDC claims.

### Basic command

```bash
uv run gen-jwt.py test-user.yaml
```

If you don't use `uv`, run with Python directly (inside a `.venv`):

```bash
python3 gen-jwt.py test-user.yaml
```

### Example YAML file (`test-user.yaml`)

```yaml
sub: "test-user-123"
email: "test.user@example.com"
name: "Test User"
preferred_username: "tuser"
groups:
  - "developer"
  - "admin"
tenant: "tenant-A"
```

#### Required YAML fields

| Field                | Type       | Description                          |
|----------------------|------------|--------------------------------------|
| `sub`                | `string`   | Subject / unique user identifier     |
| `email`              | `string`   | User email address                   |
| `name`               | `string`   | Full display name                    |
| `preferred_username` | `string`   | Short login username                 |
| `groups`             | `string[]` | List of group / role memberships     |

#### Optional YAML fields

| Field    | Type     | Default      | Description        |
|----------|----------|--------------|--------------------|
| `tenant` | `string` | `"tenant-A"` | Tenant identifier  |

## Modified Files

When run, the script automatically locates the mock JSON definitions relative to the script path and updates them. `git status` will show the changes.

1. **`../wiremock/oidc/mappings/jwks.json`**
   Updates `response.jsonBody.keys` with the newly generated `kid`, `n`, `e`, etc., so WireMock exposes the correct public key for Quarkus OIDC to verify against.

2. **`../wiremock/oidc/mappings/token.json`**
   Updates `response.jsonBody.access_token` with the new signed `header.payload.signature` token string so WireMock responds with the correct test user.

## Custom Arguments

You can optionally override some token configuration arguments via the CLI:

| Argument | Default                                    | Description              |
|----------|--------------------------------------------|--------------------------|
| `--kid`  | `test-key-2026`                            | Key ID                   |
| `--iss`  | `http://localhost:8090/realms/master`       | Issuer claim             |
| `--aud`  | `k8s-auth-sidecar`                         | Audience claim           |
| `--iat`  | `1771572840`                               | Issued At timestamp      |
| `--exp`  | `2086932840`                               | Expiration timestamp     |

Example with custom arguments:

```bash
uv run gen-jwt.py james-bond.yaml --iss https://auth.my-domain.com --exp 1782000000
```
