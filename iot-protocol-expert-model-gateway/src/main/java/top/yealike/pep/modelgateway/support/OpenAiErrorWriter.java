package top.yealike.pep.modelgateway.support;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/** OpenAI 兼容入口的错误体，不使用内部统一响应信封。 */
public final class OpenAiErrorWriter {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private OpenAiErrorWriter() {}

    public static Map<String, Object> body(int status, String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message == null || message.isBlank() ? "请求失败" : message);
        error.put("type", type(status));
        error.put("param", null);
        error.put("code", code);
        return Map.of("error", error);
    }

    public static void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        JSON.writeValue(response.getWriter(), body(status, code, message));
    }

    private static String type(int status) {
        return switch (status) {
            case 400 -> "invalid_request_error";
            case 401, 403 -> "authentication_error";
            case 404 -> "not_found_error";
            case 429 -> "rate_limit_error";
            default -> "server_error";
        };
    }
}
