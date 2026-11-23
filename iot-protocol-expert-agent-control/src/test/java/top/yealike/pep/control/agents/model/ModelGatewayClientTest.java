package top.yealike.pep.control.agents.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import top.yealike.pep.control.config.ModelGatewayProperties;
import top.yealike.pep.control.support.AiExceptions.AiException;
import top.yealike.pep.model.contract.ModelCallHeaders;

class ModelGatewayClientTest {

    private ModelGatewayProperties properties;
    private MockRestServiceServer server;
    private ModelGatewayClient client;

    @BeforeEach
    void setUp() {
        properties = new ModelGatewayProperties();
        properties.setBaseUrl("http://localhost:19085");
        properties.setServiceToken("test-service-token");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ModelGatewayClient(properties, builder);
    }

    @Test
    void missingTokenKeepsConfigurationErrorWithoutSendingRequest() {
        properties.setServiceToken("");

        assertThatThrownBy(() -> client.quota("USER", "user-1"))
                .isInstanceOf(AiException.class)
                .hasMessage("未配置模型网关服务令牌");
        server.verify();
    }

    @Test
    void missingAddressKeepsConfigurationErrorWithoutSendingRequest() {
        properties.setBaseUrl("");

        assertThatThrownBy(() -> client.quota("USER", "user-1"))
                .isInstanceOf(AiException.class)
                .hasMessage("未配置模型网关地址");
        server.verify();
    }

    @Test
    void gatewayAuthenticationFailureDoesNotExpireBrowserSessionOrExposeResponse() {
        server.expect(requestTo("http://localhost:19085/api/model-gateway/v1/me/quota?scopeKind=USER&subjectId=user-1"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("private upstream details"));

        assertThatThrownBy(() -> client.quota("USER", "user-1"))
                .isInstanceOfSatisfying(AiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(503);
                    assertThat(exception.getMessage()).contains("共享令牌配置").doesNotContain("private upstream details");
                });
        server.verify();
    }

    @Test
    void quotaQueryUsesServiceTokenAndReturnsSnapshot() {
        server.expect(requestTo("http://localhost:19085/api/model-gateway/v1/me/quota?scopeKind=USER&subjectId=user-1"))
                .andExpect(header("X-PEP-Service-Token", "test-service-token"))
                .andRespond(withSuccess("""
                        {"code":"OK","msg":"Success","data":{"scopeKind":"USER","subjectId":"user-1",
                        "policies":[],"availableUnits":0,"pendingReconciliation":0}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.quota("USER", "user-1")).satisfies(snapshot -> {
            assertThat(snapshot.subjectId()).isEqualTo("user-1");
            assertThat(snapshot.pendingReconciliation()).isZero();
        });
        server.verify();
    }

    @Test
    void usageFailureDoesNotExposeGatewayErrorBody() {
        server.expect(
                        requestTo(
                                "http://localhost:19085/api/model-gateway/v1/me/usage?scopeKind=USER&subjectId=user-1&limit=100"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("private upstream details"));

        assertThatThrownBy(() -> client.usage("USER", "user-1", 100, null, null))
                .isInstanceOf(AiException.class)
                .hasMessage("模型网关查询暂不可用");
        server.verify();
    }

    @Test
    void chatSendsLogicalCallHeaders() {
        server.expect(requestTo("http://localhost:19085/v1/chat/completions"))
                .andExpect(header("Authorization", "Bearer run-token"))
                .andExpect(header(ModelCallHeaders.ATTEMPT_NO, "1"))
                .andExpect(request -> {
                    String logicalCallId = request.getHeaders().getFirst(ModelCallHeaders.LOGICAL_CALL_ID);
                    assertThat(logicalCallId).isNotBlank();
                    assertThat(UUID.fromString(logicalCallId)).isNotNull();
                })
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.chat("run-token", java.util.Map.of("model", "test"))).containsKey("choices");
        server.verify();
    }
}
