package org.reportservice.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The {@code RestClient}s of report-service's remote calls (D2, D5, D6): auth-service (C5 for the access checker, C7
 * for the CI upload) and scenario-service (planning). Timeouts live in the request factory: the JDK
 * {@code HttpClient}'s connect timeout and the factory's read timeout. Each client speaks HTTP/1.1 and follows no
 * redirect, and every call is one attempt on one connection: the JDK client has no per-client retry switch, so
 * {@code ReportServiceApplication} limits it JVM-wide at start-up (RE6).
 */
@Configuration
public class RestClientConfiguration {

    @Bean
    public RestClient authServiceRestClient(RestClient.Builder builder, AuthServiceProperties properties) {
        return client(builder, properties.baseUrl(), properties.connectTimeout(), properties.readTimeout());
    }

    @Bean
    public RestClient scenarioServiceRestClient(RestClient.Builder builder, ScenarioServiceProperties properties) {
        return client(builder, properties.baseUrl(), properties.connectTimeout(), properties.readTimeout());
    }

    private static RestClient client(RestClient.Builder builder, URI baseUrl, Duration connectTimeout,
                                     Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return builder.clone()
                .baseUrl(baseUrl.toString())
                .requestFactory(requestFactory)
                .build();
    }
}
