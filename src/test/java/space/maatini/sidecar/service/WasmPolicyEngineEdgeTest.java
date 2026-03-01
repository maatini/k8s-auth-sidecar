package space.maatini.sidecar.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import space.maatini.sidecar.config.SidecarConfig;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.Mockito.when;

@io.quarkus.test.junit.QuarkusTest
public class WasmPolicyEngineEdgeTest {

    @Test
    void testEdgeCases() throws Exception {
        WasmPolicyEngine engine = new WasmPolicyEngine();
        engine.config = Mockito.mock(SidecarConfig.class);
        engine.objectMapper = new ObjectMapper();

        SidecarConfig.OpaConfig opaConfig = Mockito.mock(SidecarConfig.OpaConfig.class);
        SidecarConfig.OpaConfig.EmbeddedOpaConfig embedConfig = Mockito
                .mock(SidecarConfig.OpaConfig.EmbeddedOpaConfig.class);
        when(engine.config.opa()).thenReturn(opaConfig);
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

        // 5. Trigger Shutdown
        engine.shutdown();
    }
}
