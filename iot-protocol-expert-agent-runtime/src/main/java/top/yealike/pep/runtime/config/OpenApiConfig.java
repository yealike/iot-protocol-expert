package top.yealike.pep.runtime.config;

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
import io.swagger.v3.oas.models.parameters.Parameter;
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
import top.yealike.pep.runtime.contract.InternalSignature;

/** OpenAPI 元数据与 Knife4j 适配；保留显式声明的 operationId。 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    private static final String JSON = "application/json";
    private static final String API_RESPONSE_REF = "#/components/schemas/ApiResponse";

    @Bean
    public OpenAPI agentRuntimeOpenApi() {
        Components components = new Components();
        ModelConverters.getInstance()
                .read(top.yealike.pep.observability.api.ApiResponse.class)
                .forEach(components::addSchemas);
        return new OpenAPI()
                .info(new Info()
                        .title("Agent Runtime 内部 API")
                        .version("1.0.0")
                        .description("Agent Control 调用的内部运行接口；除健康检查外均要求 HMAC-SHA256 签名。"))
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
                addInternalHeaders(customized);
                addCommonErrors(customized);
                wrapSuccessResponses(customized, handlerMethod);
                return customized;
            }
        };
    }

    private static void addInternalHeaders(Operation operation) {
        addHeader(operation, InternalSignature.HEADER_SERVICE, "调用方服务名", "iot-protocol-expert-agent-control");
        addHeader(operation, InternalSignature.HEADER_TIMESTAMP, "Unix 秒级时间戳", "1763890000");
        addHeader(operation, InternalSignature.HEADER_NONCE, "一次性随机串", "6f7a9c2d4e8b1a3f");
        addHeader(operation, InternalSignature.HEADER_SIGNATURE, "HMAC-SHA256 签名", "9f2c4e7a6b1d8f30");
    }

    private static void addHeader(Operation operation, String name, String description, String example) {
        operation.addParametersItem(new Parameter()
                .name(name)
                .in("header")
                .required(true)
                .description(description)
                .example(example));
    }

    private static void addCommonErrors(Operation operation) {
        addError(operation, "400", "请求格式或参数无效");
        addError(operation, "401", "内部签名或服务身份校验失败");
        addError(operation, "500", "Runtime 执行或依赖异常");
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
