package top.yealike.pep.modelgateway.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import top.yealike.pep.modelgateway.catalog.CatalogRecords;
import top.yealike.pep.modelgateway.catalog.CatalogService;

class ProviderClientTest {

    @Test
    void streamWithoutCompletionMarkerIsReportedAsInterrupted() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://provider.test/v1/chat/completions"))
                .andRespond(withSuccess(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n",
                        MediaType.TEXT_EVENT_STREAM));
        ProviderClient client = new ProviderClient(builder.build());

        assertThatThrownBy(() -> client.stream(model(), "{}", line -> {}))
                .isInstanceOf(ProviderClient.UpstreamStreamException.class);
        server.verify();
    }

    @Test
    void streamWithCompletionMarkerFinishesNormally() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://provider.test/v1/chat/completions"))
                .andRespond(withSuccess("data: {\"choices\":[]}\n\ndata: [DONE]\n\n", MediaType.TEXT_EVENT_STREAM));
        ProviderClient client = new ProviderClient(builder.build());
        StringBuilder lines = new StringBuilder();

        assertThatCode(() ->
                        client.stream(model(), "{}", line -> lines.append(line).append('\n')))
                .doesNotThrowAnyException();
        assertThat(lines.toString()).contains("data: [DONE]");
        server.verify();
    }

    private static CatalogService.ResolvedModel model() {
        UUID providerId = UUID.randomUUID();
        CatalogRecords.Provider provider = new CatalogRecords.Provider(
                providerId,
                "test-provider",
                "Test Provider",
                "http://provider.test/v1",
                new byte[0],
                new byte[0],
                "ACTIVE");
        CatalogRecords.ModelProfile profile = new CatalogRecords.ModelProfile(
                UUID.randomUUID(),
                "test-model",
                "Test Model",
                providerId,
                "upstream-model",
                8_192,
                1_024,
                1,
                1,
                "CONSERVATIVE_ESTIMATE",
                "ACTIVE");
        return new CatalogService.ResolvedModel(profile, provider, "test-secret");
    }
}
