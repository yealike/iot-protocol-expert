package top.yealike.ipe.simulator.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import top.yealike.ipe.simulator.config.SimulatorProperties;
import top.yealike.ipe.simulator.runtime.SimulatorRuntime;

/** 模拟器同源桥接：协议目录/解码转发协议通道，设备命令与费率转发运维平台。 浏览器只访问模拟器，不直接持有平台会话或服务令牌。 */
@RestController
public class PlatformProxyController {

    private final SimulatorProperties properties;
    private final SimulatorRuntime runtime;
    private final ObjectMapper json;
    private final RestClient client;

    public PlatformProxyController(
            SimulatorProperties properties, SimulatorRuntime runtime, ObjectMapper json, RestClient.Builder builder) {
        this.properties = properties;
        this.runtime = runtime;
        this.json = json;
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.client = builder.requestFactory(factory).build();
    }

    @RequestMapping(
            value = "/api/platform/{*path}",
            method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<String> proxy(
            @PathVariable String path,
            @RequestBody(required = false) String body,
            jakarta.servlet.http.HttpServletRequest request) {
        String p = path.startsWith("/") ? path : "/" + path;
        try {
            if (p.equals("/rate-models")) {
                return forward(
                        HttpMethod.valueOf(request.getMethod()), opsBase() + "/api/ops/simulator/rate-models", body);
            }
            if (p.startsWith("/protocol/")) {
                return forward(HttpMethod.valueOf(request.getMethod()), protocolBase() + "/api" + p, body);
            }
            if (!p.startsWith("/devices/")) {
                return bad("不支持的平台操作");
            }
            return deviceCommand(p, request.getMethod(), body);
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                return ResponseEntity.status(503).body("{\"message\":\"平台请求中断\"}");
            }
            return ResponseEntity.status(502)
                    .body("{\"message\":\"无法访问配置的平台服务，请检查 ops-base-url / platform-http-port\"}");
        }
    }

    private ResponseEntity<String> deviceCommand(String path, String method, String rawBody) throws Exception {
        String[] parts = path.split("/");
        // /devices/{code}/{action} 或 /devices/{code}/cards/{op}
        if (parts.length < 4) {
            return bad("设备操作路径不完整");
        }
        String code = parts[2];
        Map<String, Object> state = runtime.state();
        Object deviceIdValue = state.get("deviceId");
        if (deviceIdValue == null) {
            return bad("请先从设备列表选择一台设备");
        }
        long deviceId = ((Number) deviceIdValue).longValue();
        if (state.get("pileCode") != null && !code.equals(String.valueOf(state.get("pileCode")))) {
            return bad("设备编号与当前选中设备不一致");
        }
        String action = parts[3];
        if (parts.length == 5 && "cards".equals(action)) {
            action = "cards/" + parts[4];
        }
        Map<String, Object> body = parseBody(rawBody);
        if ("startRequest".equals(action)) action = "start";
        if ("stopRequest".equals(action)) action = "stop";

        if ("parallel-start".equals(action)) {
            return bad("并充需要从运维平台按设备能力发起");
        }
        if ("start".equals(action)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("deviceId", deviceId);
            payload.put("gunNo", text(body, "gunNo", "01"));
            boolean ocpp = "ocpp16j".equalsIgnoreCase(String.valueOf(state.get("protocol")));
            payload.put("idTag", text(body, "logicalCard", ocpp ? text(body, "physicalCard", "IPE-APP") : "0"));
            payload.put("physicalCard", text(body, "physicalCard", null));
            payload.put("balance", text(body, "balance", "1000.00"));
            return forward(
                    HttpMethod.POST, opsBase() + "/api/ops/simulator/commands/start", json.writeValueAsString(payload));
        }
        if ("stop".equals(action)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("deviceId", deviceId);
            payload.put("gunNo", text(body, "gunNo", "01"));
            return forward(
                    HttpMethod.POST, opsBase() + "/api/ops/simulator/commands/stop", json.writeValueAsString(payload));
        }

        String type = switch (action) {
            case "cards/sync" -> "cards-sync";
            case "cards/clear" -> "cards-clear";
            case "cards/query" -> "cards-query";
            default -> action;
        };
        Map<String, Object> params = body;
        if (type.equals("cards-clear") || type.equals("cards-query")) {
            params = new LinkedHashMap<>();
            Object physicalCards = body.get("physicalCards");
            params.put("cards", physicalCards instanceof List<?> list ? list : List.of());
        } else if (type.equals("cards-sync")) {
            params = new LinkedHashMap<>();
            Object cards = body.get("cards");
            params.put("cards", cards instanceof List<?> list ? list : List.of());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deviceId", deviceId);
        payload.put("type", type);
        payload.put("params", params);
        return forward(HttpMethod.POST, opsBase() + "/api/ops/simulator/commands", json.writeValueAsString(payload));
    }

    private Map<String, Object> parseBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Object parsed = json.readValue(rawBody, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((k, v) -> result.put(String.valueOf(k), v));
                return result;
            }
        } catch (RuntimeException ignored) {
        }
        return new LinkedHashMap<>();
    }

    private static String text(Map<String, Object> body, String key, String fallback) {
        Object value = body.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private ResponseEntity<String> forward(HttpMethod method, String url, String body) {
        var outgoing = client.method(method)
                .uri(URI.create(url))
                .header("X-Ops-Simulator-Token", properties.getOpsSimulatorToken())
                .contentType(MediaType.APPLICATION_JSON);
        if (method == HttpMethod.POST) {
            outgoing.body(body == null || body.isBlank() ? "{}" : body);
        }
        return outgoing.exchange((sent, response) -> ResponseEntity.status(response.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    private static ResponseEntity<String> bad(String message) {
        return ResponseEntity.badRequest().body("{\"message\":\"" + message + "\",\"success\":false}");
    }

    private String opsBase() {
        return properties.getOpsBaseUrl().replaceAll("/$", "");
    }

    private String protocolBase() {
        return "http://" + properties.getPlatformHost() + ":" + properties.getPlatformHttpPort();
    }
}
