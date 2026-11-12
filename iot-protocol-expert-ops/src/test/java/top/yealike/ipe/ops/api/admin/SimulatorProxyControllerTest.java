package top.yealike.ipe.ops.api.admin;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class SimulatorProxyControllerTest {
    @Test
    void forwardsMethodQueryBodyAndErrorStatus() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/fleet/12/start", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("code=1", exchange.getRequestURI().getRawQuery());
            assertEquals("{}", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"message\":\"failed\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(409, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var proxy = new SimulatorProxyController("http://127.0.0.1:" + server.getAddress().getPort());
            var request = new MockHttpServletRequest("POST", "/api/simulator-proxy/fleet/12/start");
            request.setQueryString("code=1");
            var response = proxy.forward(request, "{}".getBytes(StandardCharsets.UTF_8));
            assertEquals(409, response.getStatusCode().value());
            assertEquals("{\"message\":\"failed\"}", new String((byte[]) response.getBody(), StandardCharsets.UTF_8));
            assertEquals(400, proxy.forward(new MockHttpServletRequest("GET", "/api/simulator-proxy/../state"), null).getStatusCode().value());
        } finally {
            server.stop(0);
        }
    }
}
