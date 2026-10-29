package com.example.authservice.configuration;

import com.example.authservice.enums.OpenEndpoints;
import com.example.authservice.security.ProblemAccessDeniedHandler;
import com.example.authservice.security.ProblemAuthenticationEntryPoint;
import com.example.authservice.security.ServiceKeyAuthenticationFilter;
import com.example.authservice.service.impl.CustomUserDetailsService;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.Arrays;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    private final CustomUserDetailsService userDetailsService;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ProblemAuthenticationEntryPoint authenticationEntryPoint;
    private final ProblemAccessDeniedHandler accessDeniedHandler;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login",
                                "/api/auth/invitations/{token}/accept").permitAll()
                        .requestMatchers(Arrays.stream(OpenEndpoints.values()).map(OpenEndpoints::getUrl).toArray(String[]::new)).permitAll()
                        .anyRequest().authenticated()
                );

        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * C7 (report-service spec D5, AE1): {@code /internal/service/**} authenticates services by {@code X-Service-Key}
     * only, never by a user's token; without the right key (or with the key unset) every request is 401. It's ordered
     * before the chain above, which keeps every other path.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain internalServiceFilterChain(HttpSecurity http, InternalServiceKey serviceKey)
            throws Exception {
        http
                .securityMatcher("/internal/service/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(authenticationEntryPoint))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .addFilterBefore(new ServiceKeyAuthenticationFilter(serviceKey),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
