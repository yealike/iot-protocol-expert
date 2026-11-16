package top.yealike.ipe.agent.runtime.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import top.yealike.ipe.agent.runtime.TestFixtures;
import top.yealike.ipe.ai.contract.InternalSignature;

class ControlClientTest {

    @Test
    void signsAsRuntimeForControlAudience() throws Exception {
        AtomicBoolean signatureValid = new AtomicBoolean();
        AtomicBoolean serviceHeaderValid = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/ai/v1/runtime/readiness", exchange -> {
            handle(exchange, signatureValid, serviceHeaderValid);
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            ControlClient client = new ControlClient(
                    RestClient.builder().build(),
                    JsonMapper.builder().build(),
                    TestFixtures.properties(baseUrl, "test-secret"));

            assertThat(client.readiness()).isTrue();
            assertThat(serviceHeaderValid).isTrue();
            assertThat(signatureValid).isTrue();
        } finally {
            server.stop(0);
        }
    }

    private static void handle(HttpExchange exchange, AtomicBoolean signatureValid, AtomicBoolean serviceHeaderValid)
            throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        String pathAndQuery = exchange.getRequestURI().getRawPath()
                + (exchange.getRequestURI().getRawQuery() == null
                        ? ""
                        : "?" + exchange.getRequestURI().getRawQuery());
        String service = exchange.getRequestHeaders().getFirst(InternalSignature.HEADER_SERVICE);
        String keyId = exchange.getRequestHeaders().getFirst(InternalSignature.HEADER_KEY_ID);
        String timestamp = exchange.getRequestHeaders().getFirst(InternalSignature.HEADER_TIMESTAMP);
        String nonce = exchange.getRequestHeaders().getFirst(InternalSignature.HEADER_NONCE);
        String signature = exchange.getRequestHeaders().getFirst(InternalSignature.HEADER_SIGNATURE);
        serviceHeaderValid.set("runtime".equals(service) && "runtime".equals(keyId));
        signatureValid.set(InternalSignature.verify(
                "test-secret", signature, "GET", pathAndQuery, body, timestamp, nonce, "control"));

        byte[] response = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
