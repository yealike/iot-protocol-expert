package top.yealike.pep.modelgateway.support;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handle(ApiException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", exception.code());
        body.put("message", exception.getMessage());
        return ResponseEntity.status(exception.status()).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handle(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "MODEL_REQUEST_INVALID", "message", exception.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handle(RuntimeException exception) {
        log.error("model gateway request failed", exception);
        return ResponseEntity.internalServerError().body(Map.of("error", "MODEL_GATEWAY_ERROR", "message", "模型网关暂不可用"));
    }
}
