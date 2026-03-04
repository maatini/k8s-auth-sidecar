package space.maatini.sidecar.service;

import io.vertx.mutiny.core.buffer.Buffer;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import static org.junit.jupiter.api.Assertions.*;

class ProxyServiceExtTest {

    @Test
    void testProxyResponseIsSuccess() throws Exception {
        Class<?> proxyResponseClass = Class.forName("space.maatini.sidecar.service.ProxyService$ProxyResponse");
        
        // Use static error method
        Method errorMethod = proxyResponseClass.getDeclaredMethod("error", int.class, String.class);
        errorMethod.setAccessible(true);
        
        Object response200 = proxyResponseClass.getDeclaredConstructor(int.class, String.class, Map.class, Buffer.class)
                .newInstance(200, "OK", Map.of(), null);
        Object response299 = proxyResponseClass.getDeclaredConstructor(int.class, String.class, Map.class, Buffer.class)
                .newInstance(299, "OK", Map.of(), null);
        Object response300 = proxyResponseClass.getDeclaredConstructor(int.class, String.class, Map.class, Buffer.class)
                .newInstance(300, "OK", Map.of(), null);
                
        Method isSuccessMethod = proxyResponseClass.getDeclaredMethod("isSuccess");
        
        assertTrue((Boolean) isSuccessMethod.invoke(response200));
        assertTrue((Boolean) isSuccessMethod.invoke(response299));
        assertFalse((Boolean) isSuccessMethod.invoke(response300));
        
        Object errorResponse = errorMethod.invoke(null, 500, "Internal Server Error");
        assertFalse((Boolean) isSuccessMethod.invoke(errorResponse));
    }
}
