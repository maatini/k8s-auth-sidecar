package space.maatini.sidecar.filter;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import jakarta.ws.rs.core.Response;
import static org.junit.jupiter.api.Assertions.*;

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
}
