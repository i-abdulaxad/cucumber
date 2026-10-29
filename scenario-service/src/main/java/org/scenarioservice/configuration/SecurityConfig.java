package org.scenarioservice.configuration;

import jakarta.servlet.DispatcherType;
import org.scenarioservice.security.JwtAuthenticationFilter;
import org.scenarioservice.security.JwtTokenValidator;
import org.scenarioservice.security.ProblemAccessDeniedHandler;
import org.scenarioservice.security.ProblemAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * The security chain (D6, T3): stateless, no CSRF, no CORS (the gateway owns CORS), no session, no form or basic
 * login; every request needs a valid bearer token except the container's error dispatch (platform-hardening D4).
 * Project access is checked by each use case through the access checker (D5), not here.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtTokenValidator tokenValidator,
                                                   ProblemAuthenticationEntryPoint authenticationEntryPoint,
                                                   ProblemAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .cors(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
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
     * scenario-service authenticates bearer tokens only. This manager refuses every username and password, and its
     * presence stops Boot from creating a default user with a generated password that it would log.
     */
    @Bean
    public AuthenticationManager noPasswordAuthentication() {
        return authentication -> {
            throw new ProviderNotFoundException("scenario-service accepts bearer tokens only");
        };
    }
}
