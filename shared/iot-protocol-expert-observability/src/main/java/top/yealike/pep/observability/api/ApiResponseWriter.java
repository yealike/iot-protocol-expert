package top.yealike.pep.observability.api;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/** 在 MVC 之外（过滤器、安全入口点）写出统一的 JSON 错误信封。 */
public final class ApiResponseWriter {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ApiResponseWriter() {}

    public static void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        JSON.writeValue(response.getWriter(), ApiResponse.failure(code, message));
    }
}
