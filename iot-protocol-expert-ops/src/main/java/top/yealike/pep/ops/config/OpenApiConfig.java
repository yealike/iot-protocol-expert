package top.yealike.pep.ops.config;

import com.github.xiaoymin.knife4j.spring.extension.Knife4jJakartaOperationCustomizer;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import top.yealike.pep.ops.api.dto.ApiError;

/** OpenAPI 元数据与 Knife4j 适配；保留显式声明的 operationId。 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    public static final String SESSION_COOKIE_SCHEME = "sessionCookie";
    public static final String SERVICE_TOKEN_SCHEME = "opsServiceToken";
    public static final String SIMULATOR_TOKEN_SCHEME = "simulatorToken";

    private static final String JSON = "application/json";
    private static final String API_ERROR_REF = "#/components/schemas/ApiError";

    @Bean
    public OpenAPI opsOpenApi() {
        Components components = new Components()
                .addSecuritySchemes(
                        SESSION_COOKIE_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("JSESSIONID")
                                .description("浏览器登录后由 OPS 签发的会话 Cookie"))
                .addSecuritySchemes(
                        SERVICE_TOKEN_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Ops-Service-Token")
                                .description("内部服务调用令牌，仅用于 /internal/**"))
                .addSecuritySchemes(
                        SIMULATOR_TOKEN_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Ops-Simulator-Token")
                                .description("模拟器专用令牌，仅用于 /api/ops/simulator/**"));
        ModelConverters.getInstance().read(ApiError.class).forEach(components::addSchemas);
        return new OpenAPI()
                .info(new Info()
                        .title("协议运营平台 API")
                        .version("1.0.0")
                        .description("充电设备、产品、订单账单、费率、遥测、协议文档、用户权限与 AI 工具内部接口。"))
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
                return customized;
            }
        };
    }

    private static void addCommonErrors(Operation operation) {
        addError(operation, "400", "请求格式或参数无效");
        addError(operation, "401", "未登录、会话失效或服务令牌无效");
        addError(operation, "403", "当前账号无权执行该操作");
        addError(operation, "409", "业务状态冲突");
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
                                    .addMediaType(JSON, new MediaType().schema(new Schema<>().$ref(API_ERROR_REF)))));
        }
    }
}
