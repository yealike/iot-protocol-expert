package top.yealike.pep.observability.api;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** 将业务 Controller 的 JSON 返回值统一包装成 {@link ApiResponse}。 */
@RestControllerAdvice(
        basePackages = {"top.yealike.pep.control.api", "top.yealike.pep.runtime.api", "top.yealike.pep.modelgateway.api"
        })
public class ApiResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return !returnType.hasMethodAnnotation(RawResponse.class)
                && !returnType.getContainingClass().isAnnotationPresent(RawResponse.class);
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (body instanceof ApiResponse<?>) {
            return body;
        }
        if (selectedContentType != null && !selectedContentType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
            return body;
        }
        return ApiResponse.success(body);
    }
}
