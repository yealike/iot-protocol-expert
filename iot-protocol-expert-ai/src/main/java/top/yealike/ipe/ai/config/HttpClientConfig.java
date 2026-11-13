package top.yealike.ipe.ai.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** 内部 HTTP 调用统一超时，禁止无限等待上游。 */
@Configuration
public class HttpClientConfig {

    @Bean
    RestClient.Builder restClientBuilder(AiProperties properties) {
        var client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.requestTimeoutMs()))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofMillis(properties.requestTimeoutMs()));
        return RestClient.builder().requestFactory(factory);
    }
}
