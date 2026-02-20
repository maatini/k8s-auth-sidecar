package space.maatini.sidecar.test;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;

import java.io.File;
import java.util.Map;

public class OpaTestResource implements QuarkusTestResourceLifecycleManager {

    private GenericContainer<?> opaContainer;

    @Override
    public Map<String, String> start() {
        // Find the absolute path to the policies directory
        File policiesDir = new File("src/main/resources/policies");

        opaContainer = new GenericContainer<>("openpolicyagent/opa:latest")
                .withCommand("run", "--server", "--addr", ":8181", "/policies")
                .withFileSystemBind(policiesDir.getAbsolutePath(), "/policies", BindMode.READ_ONLY)
                .withExposedPorts(8181);

        opaContainer.start();

        return Map.of(
                "sidecar.opa.external.url", "http://" + opaContainer.getHost() + ":" + opaContainer.getMappedPort(8181),
                "sidecar.opa.mode", "external",
                "sidecar.opa.enabled", "true");
    }

    @Override
    public void stop() {
        if (opaContainer != null) {
            opaContainer.stop();
        }
    }
}
