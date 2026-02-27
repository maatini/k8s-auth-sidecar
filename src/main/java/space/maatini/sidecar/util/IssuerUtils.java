package space.maatini.sidecar.util;

/**
 * Utility class for identity provider issuer matching and extraction.
 */
public class IssuerUtils {

    /**
     * Checks if the issuer is a Microsoft Entra ID (Azure AD) issuer.
     */
    public static boolean isEntraIssuer(String issuer) {
        return issuer != null && (issuer.contains("login.microsoftonline.com") ||
                issuer.contains("sts.windows.net") ||
                issuer.contains("login.microsoft.com"));
    }

    /**
     * Checks if the issuer is a Keycloak issuer.
     */
    public static boolean isKeycloakIssuer(String issuer) {
        return issuer != null && (issuer.contains("/realms/") ||
                issuer.contains("keycloak"));
    }

    /**
     * Extracts tenant from Keycloak issuer URL.
     * Assumes format: https://keycloak.example.com/realms/{realm}
     */
    public static String extractTenantFromIssuer(String issuer) {
        if (issuer != null && issuer.contains("/realms/")) {
            int idx = issuer.lastIndexOf("/realms/");
            return issuer.substring(idx + 8);
        }
        return null;
    }
}
