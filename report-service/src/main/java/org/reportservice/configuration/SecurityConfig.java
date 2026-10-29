package org.reportservice.configuration;

import jakarta.servlet.DispatcherType;
import org.reportservice.security.JwtAuthenticationFilter;
import org.reportservice.security.JwtTokenValidator;
import org.reportservice.security.ProblemAccessDeniedHandler;
import org.reportservice.security.ProblemAuthenticationEntryPoint;
import org.reportservice.security.UploadTokenEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * The two security chains (D5, T3): stateless, no CSRF, no CORS (the gateway owns CORS), no session, no form or basic
 * login. {@code /api/ci/**} is authenticated only by an upload token and answers 401 UPLOAD_TOKEN_INVALID otherwise;
 * every other path only by a user JWT, 401 UNAUTHENTICATED otherwise, except the container's error dispatch
 * (platform-hardening D4). A JWT is no credential on {@code /api/ci/**}, and an upload token none on user paths.
 * Project access is checked by each use case through the access checker, not here. The upload token authenticator
 * joins the CI chain with the upload endpoint (T7); until then the CI chain authenticates nothing.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    static final String CI_PATHS = "/api/ci/**";

    @Bean
    @Order(1)
    public SecurityFilterChain ciUploadFilterChain(HttpSecurity http, UploadTokenEntryPoint entryPoint,
                                                   ProblemAccessDeniedHandler accessDeniedHandler) throws Exception {
        statelessBase(http)
                .securityMatcher(CI_PATHS)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
        return http.build();
    }

    @Bean
    public SecurityFilterChain userFilterChain(HttpSecurity http, JwtTokenValidator tokenValidator,
                                               ProblemAuthenticationEntryPoint authenticationEntryPoint,
                                               ProblemAccessDeniedHandler accessDeniedHandler) throws Exception {
        statelessBase(http)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(tokenValidator),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * report-service authenticates bearer credentials only. This manager refuses every username and password, and its
     * presence stops Boot from creating a default user with a generated password that it would log.
     */
    @Bean
    public AuthenticationManager noPasswordAuthentication() {
        return authentication -> {
            throw new ProviderNotFoundException("report-service accepts bearer credentials only");
        };
    }

    private static HttpSecurity statelessBase(HttpSecurity http) throws Exception {
        return http
                .cors(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    }
}
