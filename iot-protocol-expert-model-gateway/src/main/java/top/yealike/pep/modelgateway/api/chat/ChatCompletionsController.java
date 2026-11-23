package top.yealike.pep.modelgateway.api.chat;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.ObjectMapper;
import top.yealike.pep.model.contract.ModelCallHeaders;
import top.yealike.pep.model.contract.ModelErrorCodes;
import top.yealike.pep.model.contract.RunTokenPayload;
import top.yealike.pep.modelgateway.auth.GatewayAuthFilter;
import top.yealike.pep.modelgateway.chat.ModelCallService;
import top.yealike.pep.modelgateway.chat.ProviderClient;
import top.yealike.pep.modelgateway.chat.domain.PreparedCall;
import top.yealike.pep.modelgateway.chat.domain.ProviderResponse;
import top.yealike.pep.modelgateway.support.ApiException;

/**
 * OpenAI 兼容入口。Runtime 只拿到短期运行凭据和网关地址，供应商密钥只在本进程内使用。
 */
@RestController
@Tag(name = "模型网关-OpenAI 兼容接口", description = "按运行凭据路由到逻辑模型；支持普通响应与 SSE 流式响应")
@SecurityRequirement(name = "runtimeBearer")
public class ChatCompletionsController {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionsController.class);

    private final ModelCallService calls;
    private final ProviderClient providers;
    private final ObjectMapper json;

    public ChatCompletionsController(ModelCallService calls, ProviderClient providers, ObjectMapper json) {
        this.calls = calls;
        this.providers = providers;
        this.json = json;
    }

    @Operation(
            operationId = "createChatCompletion",
            summary = "模型调用",
            description = "OpenAI 兼容 chat/completions；stream=true 时返回 SSE")
    @PostMapping(
            path = "/v1/chat/completions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public ResponseEntity<StreamingResponseBody> completions(
            @RequestBody Map<String, Object> body,
            @Parameter(hidden = true) @RequestAttribute(GatewayAuthFilter.RUN_TOKEN_ATTRIBUTE) RunTokenPayload token,
            @RequestHeader(value = ModelCallHeaders.LOGICAL_CALL_ID, required = false) String logicalCallId,
            @RequestHeader(value = ModelCallHeaders.ATTEMPT_NO, required = false) String attemptNo) {
        PreparedCall call = calls.prepare(token, body, logicalCallId, attemptNo);
        if (call.stream()) {
            return stream(call);
        }
        return complete(call);
    }

    private ResponseEntity<StreamingResponseBody> complete(PreparedCall call) {
        ProviderResponse response;
        try {
            response = providers.complete(call.model(), call.upstreamBody());
        } catch (org.springframework.web.client.ResourceAccessException exception) {
            if (hasCause(exception, java.net.ConnectException.class)) {
                calls.reject(call);
            } else {
                calls.uncertain(call, null);
            }
            throw ApiException.unavailable(ModelErrorCodes.MODEL_UNAVAILABLE, "模型供应商连接失败");
        } catch (RuntimeException exception) {
            calls.uncertain(call, null);
            throw ApiException.unavailable(ModelErrorCodes.MODEL_UNAVAILABLE, "模型供应商暂不可用");
        }
        if (response.status() >= 400) {
            handleUpstreamFailure(call, response.status(), response.body());
        }
        try {
            Map<String, Object> envelope = json.readValue(response.body(), Map.class);
            calls.settle(call, usage(envelope), null, response.body());
        } catch (RuntimeException exception) {
            calls.uncertain(call, null);
            throw ApiException.unavailable(ModelErrorCodes.MODEL_UNAVAILABLE, "模型供应商返回内容无法解析");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, response.contentType())
                .body(output -> output.write(response.body().getBytes(StandardCharsets.UTF_8)));
    }

    private ResponseEntity<StreamingResponseBody> stream(PreparedCall call) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_EVENT_STREAM);
        headers.setCacheControl("no-cache");
        StreamingResponseBody body = output -> {
            Map<String, Object>[] lastUsage = new Map[] {null};
            try {
                providers.stream(call.model(), call.upstreamBody(), line -> {
                    captureUsage(line, lastUsage);
                    writeLine(output, line);
                });
                calls.settle(call, lastUsage[0], null, null);
            } catch (ProviderClient.UpstreamHttpException exception) {
                if (isRejection(exception.status())) {
                    calls.reject(call);
                } else {
                    calls.uncertain(call, null);
                }
                try {
                    writeLine(
                            output,
                            "data: "
                                    + json.writeValueAsString(Map.of(
                                            "error",
                                            Map.of("code", ModelErrorCodes.MODEL_UNAVAILABLE, "message", "模型供应商返回错误")))
                                    + "\n");
                } catch (IOException ignored) {
                    // 客户端已断开时不再追加错误事件
                }
            } catch (ProviderClient.UpstreamStreamException exception) {
                calls.uncertain(call, null);
                log.warn(
                        "upstream stream interrupted: reservation={}",
                        call.reservation().reservationId());
            } catch (RuntimeException exception) {
                calls.uncertain(call, null);
                log.error("model stream failed", exception);
            }
        };
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private void handleUpstreamFailure(PreparedCall call, int status, String body) {
        log.warn(
                "upstream rejected model call: status={} profile={}",
                status,
                call.model().profile().key());
        if (isRejection(status)) {
            calls.reject(call);
            throw new ApiException(
                    502, ModelErrorCodes.MODEL_UNAVAILABLE, "模型供应商拒绝请求（" + status + "）：" + shorten(body, 300));
        }
        calls.uncertain(call, null);
        throw ApiException.unavailable(ModelErrorCodes.MODEL_UNAVAILABLE, "模型供应商暂不可用（" + status + "）");
    }

    private static boolean isRejection(int status) {
        return status == 400 || status == 401 || status == 403 || status == 404 || status == 422;
    }

    private static Map<String, Object> usage(Map<String, Object> envelope) {
        return envelope.get("usage") instanceof Map<?, ?> map ? castMap(map) : null;
    }

    private void captureUsage(String line, Map<String, Object>[] holder) {
        if (line == null || !line.startsWith("data:")) {
            return;
        }
        String data = line.substring(5).strip();
        if (data.isEmpty() || "[DONE]".equals(data)) {
            return;
        }
        try {
            Map<String, Object> chunk = json.readValue(data, Map.class);
            if (chunk.get("usage") instanceof Map<?, ?> map) {
                holder[0] = castMap(map);
            }
        } catch (RuntimeException ignored) {
            // 供应商扩展事件不影响转发
        }
    }

    private static void writeLine(OutputStream output, String line) throws IOException {
        output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String shorten(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
