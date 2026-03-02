package space.maatini.sidecar.test;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.logging.Logger;

public class OpaTestResource implements QuarkusTestResourceLifecycleManager {

    private GenericContainer<?> opaContainer;

    private static final Logger LOGGER = Logger.getLogger(OpaTestResource.class.getName());

    @Override
    public Map<String, String> start() {
        try {
            // Find the absolute path to the policies directory
            File policiesDir = new File("src/main/resources/policies");

            opaContainer = new GenericContainer<>("openpolicyagent/opa:latest")
                    .withCommand("run", "--server", "--addr", ":8181", "/policies")
                    .withFileSystemBind(policiesDir.getAbsolutePath(), "/policies", BindMode.READ_ONLY)
                    .withExposedPorts(8181);

            opaContainer.start();

            return Map.of(
                    "sidecar.opa.external.url",
                    "http://" + opaContainer.getHost() + ":" + opaContainer.getMappedPort(8181),
                    "sidecar.opa.mode", "external",
                    "sidecar.opa.enabled", "true");
        } catch (Exception e) {
            LOGGER.warning("Could not start OPA container: " + e.getMessage() + ". Skipping OPA integration tests.");
            return Collections.emptyMap();
        }
    }

    @Override
    public void stop() {
        if (opaContainer != null) {
            opaContainer.stop();
        }
    }
}
