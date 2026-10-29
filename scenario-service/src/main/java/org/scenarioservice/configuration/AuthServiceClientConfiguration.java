package org.scenarioservice.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * The {@code RestClient} for auth-service's access check (D4). Its timeouts live in the request factory: the JDK
 * {@code HttpClient}'s connect timeout and the factory's read timeout (spring-web 7.0, no extra dependency). It
 * follows no redirect and speaks HTTP/1.1. Each access check is one attempt on one connection (platform-hardening D2):
 * the JDK client has no per-client retry switch, so {@code ScenarioServiceApplication} limits it JVM-wide at start-up.
 */
@Configuration
public class AuthServiceClientConfiguration {

    @Bean
    public RestClient authServiceRestClient(RestClient.Builder builder, AuthServiceProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return builder.clone()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }
}
