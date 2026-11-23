package top.yealike.pep.control.config;

import com.github.xiaoymin.knife4j.spring.extension.Knife4jJakartaOperationCustomizer;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import top.yealike.pep.observability.api.RawResponse;

/** Control 的 OpenAPI 元数据与统一响应 Schema。 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    private static final String JSON = "application/json";
    private static final String API_RESPONSE_REF = "#/components/schemas/ApiResponse";

    @Bean
    public OpenAPI agentControlOpenApi() {
        Components components = new Components();
        ModelConverters.getInstance()
                .read(top.yealike.pep.observability.api.ApiResponse.class)
                .forEach(components::addSchemas);
        return new OpenAPI()
                .info(new Info().title("Agent Control API").version("1.0.0").description("Agent 管理、会话、Run 与内部编排接口。"))
                .components(components);
    }

    @Bean
    public Knife4jJakartaOperationCustomizer knife4jJakartaOperationCustomizer() {
        return new Knife4jJakartaOperationCustomizer() {
            @Override
            public Operation customize(Operation operation, HandlerMethod handlerMethod) {
                io.swagger.v3.oas.annotations.Operation annotation = AnnotatedElementUtils.findMergedAnnotation(
                        handlerMethod.getMethod(), io.swagger.v3.oas.annotations.Operation.class);
                Operation customized = super.customize(operation, handlerMethod);
                if (annotation != null && StringUtils.hasText(annotation.operationId())) {
                    customized.setOperationId(annotation.operationId());
                }
                addCommonErrors(customized);
                wrapSuccessResponses(customized, handlerMethod);
                return customized;
            }
        };
    }

    private static void addCommonErrors(Operation operation) {
        addError(operation, "400", "请求格式或参数无效");
        addError(operation, "401", "未登录或会话已过期");
        addError(operation, "403", "无权执行此操作");
        addError(operation, "404", "资源不存在");
        addError(operation, "409", "资源版本冲突");
        addError(operation, "500", "服务内部异常");
    }

    private static void addError(Operation operation, String code, String description) {
        ApiResponses responses = operation.getResponses();
        if (responses == null) {
            responses = new ApiResponses();
            operation.setResponses(responses);
        }
        if (!responses.containsKey(code)) {
            responses.addApiResponse(
                    code,
                    new ApiResponse()
                            .description(description)
                            .content(new Content()
                                    .addMediaType(
                                            JSON, new MediaType().schema(new Schema<>().$ref(API_RESPONSE_REF)))));
        }
    }

    private static void wrapSuccessResponses(Operation operation, HandlerMethod handlerMethod) {
        if (handlerMethod.hasMethodAnnotation(RawResponse.class)
                || handlerMethod.getBeanType().isAnnotationPresent(RawResponse.class)
                || operation.getResponses() == null) {
            return;
        }
        operation.getResponses().forEach((status, response) -> {
            if (!status.startsWith("2") || response.getContent() == null) {
                return;
            }
            response.getContent().forEach((mediaType, content) -> {
                if (!isJson(mediaType) || content.getSchema() == null || isApiResponse(content.getSchema())) {
                    return;
                }
                Schema<?> data = content.getSchema();
                ObjectSchema envelope = new ObjectSchema();
                envelope.addProperty(
                        "code", new StringSchema().example(top.yealike.pep.observability.api.ApiResponse.SUCCESS_CODE));
                envelope.addProperty(
                        "msg",
                        new StringSchema().example(top.yealike.pep.observability.api.ApiResponse.SUCCESS_MESSAGE));
                envelope.addProperty("data", data);
                envelope.setRequired(List.of("code", "msg", "data"));
                content.setSchema(envelope);
            });
        });
    }

    private static boolean isJson(String mediaType) {
        String normalized = mediaType.toLowerCase(Locale.ROOT);
        return "application/json".equals(normalized) || normalized.endsWith("+json");
    }

    private static boolean isApiResponse(Schema<?> schema) {
        return schema.get$ref() != null && schema.get$ref().endsWith("/ApiResponse");
    }
}
