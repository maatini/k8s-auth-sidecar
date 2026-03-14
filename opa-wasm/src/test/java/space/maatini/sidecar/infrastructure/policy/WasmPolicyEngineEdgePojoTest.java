package space.maatini.sidecar.infrastructure.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import space.maatini.sidecar.infrastructure.config.SidecarConfig;
import space.maatini.sidecar.application.service.PolicyService;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.Mockito.when;

public class WasmPolicyEngineEdgePojoTest {

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void testEdgeCases() throws Exception {
        WasmPolicyEngine engine = new WasmPolicyEngine();
        setField(engine, "config", Mockito.mock(SidecarConfig.class));
        setField(engine, "objectMapper", new ObjectMapper());
        setField(engine, "policyService", Mockito.mock(PolicyService.class));

        SidecarConfig config = (SidecarConfig) getField(engine, "config");
        SidecarConfig.OpaConfig opaConfig = Mockito.mock(SidecarConfig.OpaConfig.class);
        SidecarConfig.OpaConfig.EmbeddedOpaConfig embedConfig = Mockito
                .mock(SidecarConfig.OpaConfig.EmbeddedOpaConfig.class);
        when(config.opa()).thenReturn(opaConfig);
        when(opaConfig.embedded()).thenReturn(embedConfig);

        // 1. Classpath missing leading slash
        when(embedConfig.wasmPath()).thenReturn("classpath:does-not-exist.wasm");
        engine.loadWasmModule();

        // 2. Classpath with leading slash missing
        when(embedConfig.wasmPath()).thenReturn("classpath:/does-not-exist.wasm");
        engine.loadWasmModule();

        // 3. FileSystem not found
        when(embedConfig.wasmPath()).thenReturn("/does-not-exist/invalid.wasm");
        engine.loadWasmModule();

        // 4. Valid file system path with invalid wasm content to trigger parse error
        Path tempFile = Files.createTempFile("bad", ".wasm");
        Files.write(tempFile, "bad-data".getBytes());
        when(embedConfig.wasmPath()).thenReturn(tempFile.toAbsolutePath().toString());
        engine.loadWasmModule();
        Files.deleteIfExists(tempFile);
    }

    private Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
