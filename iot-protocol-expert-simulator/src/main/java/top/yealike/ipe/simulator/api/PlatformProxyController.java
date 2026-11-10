package top.yealike.ipe.simulator.api;

import top.yealike.ipe.simulator.config.SimulatorProperties;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Same-origin bridge using Boot's observed RestClient.Builder for trace propagation. */
@RestController
public class PlatformProxyController {
    private final SimulatorProperties properties;
    private final RestClient client;

    public PlatformProxyController(SimulatorProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        client = builder.requestFactory(factory).build();
    }

    @RequestMapping(value = "/api/platform/{*path}", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<String> proxy(@PathVariable String path, @RequestBody(required = false) String body,
                                        jakarta.servlet.http.HttpServletRequest request) {
        if (!path.matches("/(rate-models|protocol/(catalog|decode)|devices/[0-9]{14}(/[a-z-]+|/cards/(sync|clear|query))?)"))
            return ResponseEntity.badRequest().body("{\"message\":\"不支持的平台操作\"}");
        try {
            URI uri = new URI("http", null, properties.getPlatformHost(), properties.getPlatformHttpPort(),
                    "/api" + path, null, null);
            var outgoing = client.method(HttpMethod.valueOf(request.getMethod())).uri(uri)
                    .contentType(MediaType.APPLICATION_JSON);
            if (request.getMethod().equals("POST")) outgoing.body(body == null ? "{}" : body);
            // exchange preserves platform 4xx/5xx responses instead of throwing them away.
            return outgoing.exchange((sent, response) -> ResponseEntity.status(response.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted())
                return ResponseEntity.status(503).body("{\"message\":\"平台请求中断\"}");
            return ResponseEntity.status(502).body("{\"message\":\"无法访问配置的平台HTTP端口，请检查 platform-http-port\"}");
        }
    }
}
