package top.yealike.pep.runtime.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import top.yealike.pep.runtime.TestFixtures;
import top.yealike.pep.runtime.contract.InternalSignature;
import top.yealike.pep.runtime.contract.RunCompletionRequest;
import top.yealike.pep.runtime.contract.RunTerminalStatus;
import top.yealike.pep.runtime.registry.NacosRuntimeRegistry;

class ControlClientTest {

    @Test
    void fetchesSkillVersionsAndFilesFromControlWithoutSkillNamingInstances() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/ai/v1/skills/", exchange -> {
            requests.incrementAndGet();
            respond(
                    exchange,
                    exchange.getRequestURI().getPath().endsWith("/file")
                            ? "{\"path\":\"SKILL.md\",\"content\":\"test content\",\"sha256\":\"digest\"}"
                            : "{\"key\":\"test-skill\",\"version\":\"1.0.0\",\"bundleDigest\":\"digest\",\"files\":[]}");
        });
        server.start();
        try {
            var registry = mock(NacosRuntimeRegistry.class);
            when(registry.enabled()).thenReturn(true);
            when(registry.discoverBaseUrl("control"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
            var client = new ControlClient(
                    RestClient.builder().build(),
                    JsonMapper.builder().build(),
                    TestFixtures.properties("http://127.0.0.1:1", "test-secret"),
                    registry);

            assertThat(client.skillDescriptor("test-skill", "1.0.0").key()).isEqualTo("test-skill");
            assertThat(client.skillFile("test-skill", "1.0.0", "SKILL.md").content())
                    .isEqualTo("test content");
            assertThat(requests).hasValue(2);
            org.mockito.Mockito.verify(registry, org.mockito.Mockito.never())
                    .discoverBaseUrl(org.mockito.ArgumentMatchers.startsWith("ipe-skill"));
        } finally {
            server.stop(0);
        }
    }

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

    @Test
    void routesOrdinaryGetAndPostToDiscoveredControlInsteadOfBootstrapUrl() throws Exception {
        AtomicInteger discoveredHits = new AtomicInteger();
        HttpServer discovered = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        discovered.createContext("/internal/ai/v1/runtime/readiness", exchange -> {
            discoveredHits.incrementAndGet();
            respond(exchange, "{\"status\":\"UP\"}");
        });
        discovered.createContext("/internal/ai/v1/runs/", exchange -> {
            discoveredHits.incrementAndGet();
            respond(exchange, "{\"ok\":true}");
        });
        discovered.start();
        AtomicInteger bootstrapHits = new AtomicInteger();
        HttpServer bootstrap = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        bootstrap.createContext("/", exchange -> {
            bootstrapHits.incrementAndGet();
            respond(exchange, "{\"status\":\"UP\"}");
        });
        bootstrap.start();
        AtomicInteger replacementHits = new AtomicInteger();
        HttpServer replacement = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        replacement.createContext("/internal/ai/v1/runtime/readiness", exchange -> {
            replacementHits.incrementAndGet();
            respond(exchange, "{\"status\":\"UP\"}");
        });
        replacement.createContext("/internal/ai/v1/runs/", exchange -> {
            replacementHits.incrementAndGet();
            respond(exchange, "{\"ok\":true}");
        });
        replacement.start();
        try {
            String discoveredUrl = "http://127.0.0.1:" + discovered.getAddress().getPort();
            String bootstrapUrl = "http://127.0.0.1:" + bootstrap.getAddress().getPort();
            String replacementUrl =
                    "http://127.0.0.1:" + replacement.getAddress().getPort();
            NacosRuntimeRegistry registry = mock(NacosRuntimeRegistry.class);
            when(registry.enabled()).thenReturn(true);
            when(registry.discoverBaseUrl("control")).thenReturn(discoveredUrl);
            ControlClient client = new ControlClient(
                    RestClient.builder().build(),
                    JsonMapper.builder().build(),
                    TestFixtures.properties(bootstrapUrl, "test-secret"),
                    registry);

            assertThat(client.readiness()).isTrue();
            client.complete(new RunCompletionRequest(
                    UUID.randomUUID(),
                    3L,
                    "runtime-test",
                    "request",
                    RunTerminalStatus.SUCCEEDED,
                    "",
                    List.of(),
                    List.of(),
                    Map.of(),
                    null,
                    null));

            assertThat(discoveredHits).hasValue(2);
            when(registry.discoverBaseUrl("control")).thenReturn(replacementUrl);
            assertThat(client.readiness()).isTrue();
            client.complete(new RunCompletionRequest(
                    UUID.randomUUID(),
                    4L,
                    "runtime-test",
                    "request-2",
                    RunTerminalStatus.SUCCEEDED,
                    "",
                    List.of(),
                    List.of(),
                    Map.of(),
                    null,
                    null));
            assertThat(replacementHits).hasValue(2);
            assertThat(discoveredHits).hasValue(2);
            assertThat(bootstrapHits).hasValue(0);
        } finally {
            discovered.stop(0);
            bootstrap.stop(0);
            replacement.stop(0);
        }
    }

    @Test
    void refusesStaticFallbackWhenRegistryHasNoHealthyControl() {
        NacosRuntimeRegistry registry = mock(NacosRuntimeRegistry.class);
        when(registry.enabled()).thenReturn(true);
        when(registry.discoverBaseUrl("control")).thenReturn(null);
        ControlClient client = new ControlClient(
                RestClient.builder().build(),
                JsonMapper.builder().build(),
                TestFixtures.properties("http://127.0.0.1:1", "test-secret"),
                registry);

        assertThatThrownBy(() -> client.manifest(UUID.randomUUID()))
                .isInstanceOfSatisfying(ControlException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("CONTROL_UNAVAILABLE");
                });
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

        respond(exchange, "{\"status\":\"UP\"}");
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] response = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
