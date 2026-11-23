package top.yealike.pep.control.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;
import top.yealike.pep.observability.api.ApiResponse;
import top.yealike.pep.observability.api.ApiResponseAdvice;
import top.yealike.pep.observability.api.ApiResponseWriter;
import top.yealike.pep.observability.api.RawResponse;

class ApiResponseAdviceTest {

    private final ApiResponseAdvice advice = new ApiResponseAdvice();

    @Test
    void wrapsJsonBusinessPayloadInTheSharedEnvelope() throws Exception {
        Method method = TestController.class.getDeclaredMethod("data");

        Object body = advice.beforeBodyWrite(
                new Payload("value"), new MethodParameter(method, -1), MediaType.APPLICATION_JSON, null, null, null);

        assertThat(body).isInstanceOf(ApiResponse.class);
        ApiResponse<?> response = (ApiResponse<?>) body;
        assertThat(response.getCode()).isEqualTo(ApiResponse.SUCCESS_CODE);
        assertThat(response.getMsg()).isEqualTo(ApiResponse.SUCCESS_MESSAGE);
        assertThat(response.getData()).isEqualTo(new Payload("value"));
    }

    @Test
    void rawProtocolEndpointsAreExcluded() throws Exception {
        Method method = TestController.class.getDeclaredMethod("raw");

        assertThat(advice.supports(new MethodParameter(method, -1), null)).isFalse();
    }

    @Test
    void filterErrorsUseTheSameEnvelope() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        ApiResponseWriter.write(response, 401, "UNAUTHORIZED", "未登录或会话已过期");

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getContentAsString())
                .contains("\"code\":\"UNAUTHORIZED\"", "\"msg\":\"未登录或会话已过期\"", "\"data\":null");
    }

    @Test
    void responseEnvelopeSerializesOnlyTheDocumentedFields() throws Exception {
        String json = JsonMapper.builder().build().writeValueAsString(ApiResponse.success(new Payload("value")));

        assertThat(json)
                .contains("\"code\":\"OK\"", "\"msg\":\"Success\"", "\"data\":{\"value\":\"value\"}")
                .doesNotContain("\"success\"");
    }

    static final class TestController {
        Payload data() {
            return new Payload("value");
        }

        @RawResponse
        Payload raw() {
            return new Payload("raw");
        }
    }

    record Payload(String value) {}
}
