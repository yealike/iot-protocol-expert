package top.yealike.pep.modelgateway.api.chat;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import top.yealike.pep.modelgateway.support.ApiException;
import top.yealike.pep.modelgateway.support.OpenAiErrorWriter;

/** OpenAI 兼容入口只返回标准 error 对象，不复用内部控制面的 ApiResponse 信封。 */
@RestControllerAdvice(assignableTypes = ChatCompletionsController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ChatCompletionsExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionsExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handle(ApiException exception) {
        return response(exception.status(), exception.code(), exception.getMessage());
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class
    })
    public ResponseEntity<Map<String, Object>> malformed(Exception exception) {
        return response(400, "MODEL_REQUEST_INVALID", "请求格式或参数无效");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handle(IllegalArgumentException exception) {
        return response(400, "MODEL_REQUEST_INVALID", exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handle(Exception exception) {
        if (exception instanceof ErrorResponse error) {
            int status = error.getStatusCode().value();
            String detail = error.getBody() == null ? null : error.getBody().getDetail();
            return response(status, "HTTP_" + status, detail == null || detail.isBlank() ? "请求失败" : detail);
        }
        log.error("chat completion failed", exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR.value(), "MODEL_GATEWAY_ERROR", "模型网关暂不可用");
    }

    private static ResponseEntity<Map<String, Object>> response(int status, String code, String message) {
        return ResponseEntity.status(status).body(OpenAiErrorWriter.body(status, code, message));
    }
}
