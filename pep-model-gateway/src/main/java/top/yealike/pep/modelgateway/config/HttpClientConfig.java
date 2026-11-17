package top.yealike.pep.modelgateway.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClientConfig {

    @Bean
    RestClient upstreamRestClient(RestClient.Builder builder, GatewayProperties properties) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build());
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        return builder.requestFactory(factory).build();
    }
}
