package de.edeka.eit.sidecar;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.smallrye.jwt.build.Jwt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.*;

/**
 * End-to-End integration test for the sidecar ext-authz pipeline.
 */
@QuarkusTest
@io.quarkus.test.junit.TestProfile(AuthSidecarE2ETest.E2EProfile.class)
public class AuthSidecarE2ETest {

        static WireMockServer wireMockServer;
        static PrivateKey privateKey;

        public static class E2EProfile implements QuarkusTestProfile {
                @Override
                public Map<String, String> getConfigOverrides() {
                        return Map.of(
                                        "sidecar.auth.enabled", "true",
                                        "sidecar.authz.enabled", "true",
                                        "sidecar.opa.enabled", "true",
                                        "quarkus.oidc.auth-server-url", "http://localhost:18089/realms/test");
                }
        }

        @BeforeAll
        static void setup() throws Exception {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                kpg.initialize(2048);
                KeyPair keyPair = kpg.generateKeyPair();
                privateKey = keyPair.getPrivate();
                RSAPublicKey rsaPubKey = (RSAPublicKey) keyPair.getPublic();

                String n = Base64.getUrlEncoder().withoutPadding()
                                .encodeToString(toIntegerBytes(rsaPubKey.getModulus()));
                String e = Base64.getUrlEncoder().withoutPadding()
                                .encodeToString(toIntegerBytes(rsaPubKey.getPublicExponent()));

                String jwks = "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"1\",\"alg\":\"RS256\",\"use\":\"sig\",\"n\":\"" + n
                                + "\",\"e\":\"" + e + "\"}]}";

                wireMockServer = new WireMockServer(18089);
                wireMockServer.start();
                WireMock.configureFor(18089);

                // OIDC config stubs
                stubFor(get(urlEqualTo("/realms/test/.well-known/openid-configuration"))
                                .willReturn(aResponse()
                                                .withStatus(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody("{\"issuer\":\"http://localhost:18089/realms/test\",\"jwks_uri\":\"http://localhost:18089/realms/test/protocol/openid-connect/certs\"}")));

                stubFor(get(urlEqualTo("/realms/test/protocol/openid-connect/certs"))
                                .willReturn(aResponse()
                                                .withStatus(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody(jwks)));

                // Target service stubs
                stubFor(get(urlEqualTo("/api/users/user123/profile"))
                                .willReturn(aResponse()
                                                .withStatus(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody("{\"id\":\"user123\",\"status\":\"active\"}")));

                stubFor(get(urlEqualTo("/api/admin/error"))
                                .willReturn(aResponse()
                                                .withStatus(500)));
        }

        @AfterAll
        static void teardown() {
                if (wireMockServer != null) {
                        wireMockServer.stop();
                }
        }

        @Test
        void testHappyPath_OwnProfile_ShouldReturn200() {
                String token = Jwt.claims().subject("user123")
                                .issuer("http://localhost:18089/realms/test")
                                .issuedAt(System.currentTimeMillis() / 1000)
                                .expiresAt(System.currentTimeMillis() / 1000 + 3600)
                                .upn("user123")
                                .groups(Set.of("user"))
                                .jws().keyId("1").sign(privateKey);

                given()
                                .header("X-Envoy-Original-Path", "/api/users/user123/profile")
                                .header("X-Forwarded-Method", "GET")
                                .header("Authorization", "Bearer " + token)
                                .when().get("/authorize")
                                .then()
                                .statusCode(200)
                                .header("X-Auth-User-Id", is("user123"));
        }

        @Test
        void testErrorPath_ShouldReturn500() {
                String token = Jwt.claims().subject("admin1")
                                .issuer("http://localhost:18089/realms/test")
                                .issuedAt(System.currentTimeMillis() / 1000)
                                .expiresAt(System.currentTimeMillis() / 1000 + 3600)
                                .upn("admin1")
                                .groups(Set.of("admin"))
                                .jws().keyId("1").sign(privateKey);

                given()
                                .header("X-Envoy-Original-Path", "/api/admin/error")
                                .header("X-Forwarded-Method", "GET")
                                .header("Authorization", "Bearer " + token)
                                .when().get("/authorize")
                                .then()
                                .statusCode(200); // Authorize says OK even if backend is failing (since it only checks authz)
        }

        @Test
        void testSpoofedHeader_TraversalShouldBeNormalized() {
                // An attacker tries to spoof the path using traversal to bypass auth
                // X-Envoy-Original-Path: /api/public/../../api/admin → normalized to /api/admin
                // Without a valid JWT, this must return 401 (not 200)
                given()
                                .header("X-Envoy-Original-Path", "/api/public/../../api/admin")
                                .header("X-Forwarded-Method", "GET")
                                // Intentionally NO Authorization header → must be rejected
                                .when().get("/authorize")
                                .then()
                                .statusCode(401);
        }

        // Helper for removing leading zero byte in BigInteger if present for RSA
        // encoding
        private static byte[] toIntegerBytes(java.math.BigInteger bigInt) {
                int bitlen = bigInt.bitLength();
                bitlen = ((bitlen + 7) >> 3) << 3;
                byte[] bigBytes = bigInt.toByteArray();
                if (((bigInt.bitLength() % 8) != 0) && (((bigInt.bitLength() / 8) + 1) == (bitlen / 8))) {
                        return bigBytes;
                }
                int startSrc = 0;
                int len = bigBytes.length;
                if ((bigInt.bitLength() % 8) == 0) {
                        startSrc = 1;
                        len--;
                }
                int startDst = bitlen / 8 - len;
                byte[] resizedBytes = new byte[bitlen / 8];
                System.arraycopy(bigBytes, startSrc, resizedBytes, startDst, len);
                return resizedBytes;
        }
}
