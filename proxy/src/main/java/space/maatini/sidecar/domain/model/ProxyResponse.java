package space.maatini.sidecar.domain.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.vertx.mutiny.core.buffer.Buffer;

import java.util.Map;

/**
 * Result of a proxy request.
 */
@RegisterForReflection
public record ProxyResponse(
        int statusCode,
        String statusMessage,
        Map<String, String> headers,
        Buffer body,
        boolean isStreamed) {

    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }

    public String bodyAsString() {
        return body != null ? body.toString() : "";
    }

    public static ProxyResponse streamed(int statusCode, String statusMessage, Map<String, String> headers) {
        return new ProxyResponse(statusCode, statusMessage, headers, null, true);
    }

    public static ProxyResponse error(int statusCode, String message) {
        String sanitizedMessage = message != null ? message.replace("\"", "\\\"") : "Internal error";
        String jsonRaw = "{\"error\":\"" + sanitizedMessage + "\"}";
        return new ProxyResponse(
                statusCode,
                message,
                Map.of("Content-Type", "application/json"),
                Buffer.buffer(jsonRaw),
                false);
    }
}
