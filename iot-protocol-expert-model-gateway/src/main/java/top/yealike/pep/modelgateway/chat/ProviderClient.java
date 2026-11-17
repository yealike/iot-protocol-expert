package top.yealike.pep.modelgateway.chat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import top.yealike.pep.modelgateway.catalog.CatalogService;

@Component
public class ProviderClient {

    private final RestClient http;

    public ProviderClient(RestClient upstreamRestClient) {
        this.http = upstreamRestClient;
    }

    public record ProviderResponse(int status, String contentType, String body) {}

    public ProviderResponse complete(CatalogService.ResolvedModel model, String requestJson) {
        return http.post()
                .uri(endpoint(model))
                .header("Authorization", "Bearer " + model.secret())
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestJson)
                .exchange((request, response) -> new ProviderResponse(
                        response.getStatusCode().value(),
                        response.getHeaders().getContentType() == null
                                ? MediaType.APPLICATION_JSON_VALUE
                                : response.getHeaders().getContentType().toString(),
                        new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    public void stream(CatalogService.ResolvedModel model, String requestJson, LineSink sink) {
        http.post()
                .uri(endpoint(model))
                .header("Authorization", "Bearer " + model.secret())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .body(requestJson)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status >= 400) {
                        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        throw new UpstreamHttpException(status, body);
                    }
                    try (BufferedReader reader =
                            new BufferedReader(new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sink.accept(line);
                        }
                    } catch (IOException exception) {
                        throw new UpstreamStreamException(exception);
                    }
                    return null;
                });
    }

    private static String endpoint(CatalogService.ResolvedModel model) {
        String base = model.provider().baseUrl();
        String value = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        if (value.endsWith("/chat/completions")) {
            return value;
        }
        return value + "/chat/completions";
    }

    @FunctionalInterface
    public interface LineSink {
        void accept(String line) throws IOException;
    }

    public static class UpstreamHttpException extends RuntimeException {
        private final int status;
        private final String body;

        public UpstreamHttpException(int status, String body) {
            super("upstream returned " + status);
            this.status = status;
            this.body = body;
        }

        public int status() {
            return status;
        }

        public String body() {
            return body;
        }
    }

    public static class UpstreamStreamException extends RuntimeException {
        public UpstreamStreamException(Throwable cause) {
            super(cause);
        }
    }
}
