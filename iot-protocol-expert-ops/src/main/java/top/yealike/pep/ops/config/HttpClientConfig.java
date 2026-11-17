package top.yealike.pep.ops.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** OPS 内部 HTTP 客户端只依赖运营配置。 */
@Configuration
public class HttpClientConfig {
    @Bean
    RestClient.Builder restClientBuilder(@Value("${ipe.ops.protocol-timeout-ms:5000}") int timeoutMs) {
        var client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return RestClient.builder().requestFactory(factory);
    }
}
