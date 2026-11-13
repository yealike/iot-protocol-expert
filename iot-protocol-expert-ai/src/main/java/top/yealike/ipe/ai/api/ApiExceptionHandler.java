package top.yealike.ipe.ai.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import top.yealike.ipe.ai.support.AiExceptions.AiException;

/** 统一错误体：{error, message}，与运营平台保持一致。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(AiException.class)
    public ResponseEntity<Map<String, Object>> handle(AiException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", exception.code());
        body.put("message", exception.getMessage());
        return ResponseEntity.status(exception.status()).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "BAD_REQUEST", "message", exception.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception exception) {
        log.error("ai request failed", exception);
        return ResponseEntity.internalServerError()
                .body(Map.of("error", "INTERNAL_ERROR", "message", "服务暂时不可用，请稍后重试或联系管理员"));
    }
}
