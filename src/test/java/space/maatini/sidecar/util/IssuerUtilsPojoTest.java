package space.maatini.sidecar.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IssuerUtilsPojoTest {

    @Test
    void testIsEntraIssuer() {
        assertTrue(IssuerUtils.isEntraIssuer("https://login.microsoftonline.com/tenant-id/v2.0"));
        assertTrue(IssuerUtils.isEntraIssuer("https://sts.windows.net/tenant-id/"));
        assertTrue(IssuerUtils.isEntraIssuer("https://login.microsoft.com/common/"));
        assertFalse(IssuerUtils.isEntraIssuer("https://keycloak.example.com/realms/master"));
        assertFalse(IssuerUtils.isEntraIssuer(null));
        assertFalse(IssuerUtils.isEntraIssuer(""));
    }

    @Test
    void testIsKeycloakIssuer() {
        assertTrue(IssuerUtils.isKeycloakIssuer("https://keycloak.example.com/realms/myrealm"));
        assertTrue(IssuerUtils.isKeycloakIssuer("http://localhost:8080/realms/master"));
        assertTrue(IssuerUtils.isKeycloakIssuer("https://my-keycloak.com/realms/test"));
        assertFalse(IssuerUtils.isKeycloakIssuer("https://login.microsoftonline.com/tenant-id/v2.0"));
        assertFalse(IssuerUtils.isKeycloakIssuer(null));
        assertFalse(IssuerUtils.isKeycloakIssuer(""));
    }

    @Test
    void testExtractTenantFromIssuer() {
        assertEquals("myrealm", IssuerUtils.extractTenantFromIssuer("https://keycloak.example.com/realms/myrealm"));
        assertEquals("master", IssuerUtils.extractTenantFromIssuer("http://localhost:8080/realms/master"));
        assertNull(IssuerUtils.extractTenantFromIssuer("https://login.microsoftonline.com/tenant-id/v2.0"));
        assertNull(IssuerUtils.extractTenantFromIssuer(null));
        assertNull(IssuerUtils.extractTenantFromIssuer(""));
    }
}
