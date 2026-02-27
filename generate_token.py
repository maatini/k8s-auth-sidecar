import json
import base64

header = {"alg":"RS256","kid":"wiremock-key","typ":"JWT"}
payload = {"sub":"test-user-123","email":"test.user@example.com","name":"Test User","preferred_username":"tuser","groups":["developer","admin"],"tenant":"tenant-A","iss":"http://wiremock-oidc:8080/realms/master","aud":"k8s-auth-sidecar","iat":1771572840,"exp":2086932840}

# Since WireMock ignores the signature (as long as it matches its own JWKS, but wait.. the signature is RSA, we can't just change the payload without re-signing if the app verifies it).
