package space.maatini.sidecar.filter;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import jakarta.ws.rs.core.Response;
import static org.junit.jupiter.api.Assertions.*;

@io.quarkus.test.junit.QuarkusTest
class AuthProxyFilterExtTest {
    @Test
    void testCreateErrorResponse() throws Exception {
        AuthProxyFilter filter = new AuthProxyFilter();
        Method m = AuthProxyFilter.class.getDeclaredMethod("createErrorResponse", String.class);
        m.setAccessible(true);
        Response response = (Response) m.invoke(filter, "Test error");
        assertEquals(500, response.getStatus());

        Object entity = response.getEntity();
        assertNotNull(entity);
        assertEquals("ErrorResponse", entity.getClass().getSimpleName());

        Method codeMethod = entity.getClass().getMethod("code");
        assertEquals("error", codeMethod.invoke(entity));

        Method messageMethod = entity.getClass().getMethod("message");
        assertEquals("Test error", messageMethod.invoke(entity));
    }

    @Test
    void testFindCause() throws Exception {
        Method findCauseMethod = AuthProxyFilter.class.getDeclaredMethod("findCause", Throwable.class, Class.class);
        findCauseMethod.setAccessible(true);

        IllegalArgumentException rootCause = new IllegalArgumentException("root");
        RuntimeException wrapper = new RuntimeException("wrap", rootCause);

        Object found = findCauseMethod.invoke(null, wrapper, IllegalArgumentException.class);
        assertNotNull(found);
        assertEquals(rootCause, found);

        Object nullFound = findCauseMethod.invoke(null, wrapper, NullPointerException.class);
        assertNull(nullFound);
    }

    @Test
    void testInternalAuthException() throws Exception {
        Class<?> exceptionClass = Class.forName("space.maatini.sidecar.filter.AuthProxyFilter$InternalAuthException");
        Exception rootCause = new Exception("testing");
        Exception instance = (Exception) exceptionClass.getDeclaredConstructor(Throwable.class).newInstance(rootCause);

        assertEquals(rootCause, instance.getCause());
    }

    @Test
    void testAuthorizationDeniedException() throws Exception {
        Class<?> exceptionClass = Class
                .forName("space.maatini.sidecar.filter.AuthProxyFilter$AuthorizationDeniedException");
        space.maatini.sidecar.model.PolicyDecision decision = space.maatini.sidecar.model.PolicyDecision
                .deny("Denied by rule");
        Exception instance = (Exception) exceptionClass
                .getDeclaredConstructor(space.maatini.sidecar.model.PolicyDecision.class).newInstance(decision);

        assertEquals("Denied by rule", instance.getMessage());

        Field decisionField = exceptionClass.getDeclaredField("decision");
        decisionField.setAccessible(true);
        assertEquals(decision, decisionField.get(instance));
    }
}
