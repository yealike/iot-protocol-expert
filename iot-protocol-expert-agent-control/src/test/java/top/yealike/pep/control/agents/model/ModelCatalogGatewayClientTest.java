package top.yealike.pep.control.agents.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import top.yealike.pep.control.config.ModelGatewayProperties;
import top.yealike.pep.control.support.AiExceptions.AiException;
import top.yealike.pep.model.contract.gateway.GatewayModelRequest;
import top.yealike.pep.model.contract.gateway.GatewayProviderRequest;

class ModelCatalogGatewayClientTest {

    private MockRestServiceServer server;
    private ModelCatalogGatewayClient client;

    @BeforeEach
    void setUp() {
        ModelGatewayProperties properties = new ModelGatewayProperties();
        properties.setBaseUrl("http://localhost:19085");
        properties.setServiceToken("test-service-token");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ModelCatalogGatewayClient(properties, builder);
    }

    @Test
    void listsProvidersWithServiceToken() {
        server.expect(requestTo("http://localhost:19085/api/model-gateway/v1/admin/providers"))
                .andExpect(header("X-PEP-Service-Token", "test-service-token"))
                .andRespond(withSuccess(
                        "[{\"code\":\"deepseek\",\"credentialConfigured\":true}]", MediaType.APPLICATION_JSON));

        assertThat(client.providers())
                .singleElement()
                .satisfies(provider -> assertThat(provider.code()).isEqualTo("deepseek"));
        server.verify();
    }

    @Test
    void registersProviderWithoutEchoingSecret() {
        server.expect(requestTo("http://localhost:19085/api/model-gateway/v1/admin/providers"))
                .andExpect(header("X-PEP-Service-Token", "test-service-token"))
                .andExpect(content().json("{\"code\":\"deepseek\",\"apiKey\":\"write-only\"}"))
                .andRespond(withSuccess(
                        "{\"code\":\"deepseek\",\"credentialConfigured\":true}", MediaType.APPLICATION_JSON));

        assertThat(client.registerProvider(new GatewayProviderRequest("deepseek", null, null, "write-only"))
                        .credentialConfigured())
                .isTrue();
        server.verify();
    }

    @Test
    void registrationFailureDoesNotExposeGatewayBody() {
        server.expect(requestTo("http://localhost:19085/api/model-gateway/v1/admin/models"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("private gateway details"));

        assertThatThrownBy(
                        () -> client.registerModel(new GatewayModelRequest("bad", null, null, null, null, null, null)))
                .isInstanceOf(AiException.class)
                .hasMessage("模型网关拒绝了登记请求，请检查编码、地址和模型参数")
                .hasMessageNotContaining("private gateway details");
        server.verify();
    }
}
