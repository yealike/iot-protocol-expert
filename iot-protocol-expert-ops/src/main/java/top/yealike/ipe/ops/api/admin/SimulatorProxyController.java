package top.yealike.ipe.ops.api.admin;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/** 通过运营平台会话访问模拟器，不向浏览器暴露服务地址。 */
@RestController
public class SimulatorProxyController {
    private static final String PREFIX = "/api/simulator-proxy";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String baseUrl;

    public SimulatorProxyController(@Value("${ipe.ops.simulator-base-url:http://127.0.0.1:19081}") String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @RequestMapping(value = "/api/simulator-proxy/**", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<?> forward(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        String path = request.getRequestURI().substring(PREFIX.length());
        if (!path.matches("/[A-Za-z0-9/_-]*") || path.contains("//")) {
            return ResponseEntity.badRequest().body(Map.of("message", "模拟器路径无效"));
        }
        String query = request.getQueryString();
        URI uri = URI.create(baseUrl + "/api" + path + (query == null ? "" : "?" + query));
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30));
        if ("POST".equals(request.getMethod())) {
            builder.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body));
        } else {
            builder.GET();
        }
        try {
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return ResponseEntity.status(response.statusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", "模拟器请求中断"));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", "模拟器服务不可用，请检查服务是否启动"));
        }
    }
}
