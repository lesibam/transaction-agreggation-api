package za.co.evilcorp.transact.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String ERROR_TYPE_BASE = "https://api.transact.evilcorp.za/errors/";

    private final JwtAuthenticationFilter jwtFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                // Prometheus has no auth in prometheus.yml; metrics are non-sensitive
                // and scraped on the internal compose network.
                .requestMatchers("/actuator/prometheus").permitAll()
                .requestMatchers("/actuator/**").authenticated()
                // The dashboard (ADR-013) and docs viewer (ADR-014) are static
                // HTML/CSS/JS shells - no session, no server-side rendering of
                // customer data. The dashboard's JS calls /v1/** itself with a
                // Bearer token the user supplies; that call is what's actually
                // gated (the two rules below). Neither path exists at all when
                // its own app.ui.enabled/app.docs.enabled toggle is off -
                // UiResourceConfig/DocsResourceConfig simply don't register a
                // handler, so this permitAll is moot in that case, not a hole.
                .requestMatchers("/ui", "/ui/**", "/docs", "/docs/**", "/docs-assets/**").permitAll()
                .requestMatchers("/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/v1/customers/**").hasAnyRole("CUSTOMER", "ADMIN")
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(this::writeUnauthenticated)
                .accessDeniedHandler(this::writeAccessDenied)
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    private void writeUnauthenticated(HttpServletRequest request, HttpServletResponse response,
                                      AuthenticationException exception) throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        writeProblem(response, HttpStatus.UNAUTHORIZED,
                ERROR_TYPE_BASE + "unauthenticated",
                "Unauthenticated",
                "Authentication is required to access this resource",
                request);
    }

    private void writeAccessDenied(HttpServletRequest request, HttpServletResponse response,
                                   AccessDeniedException exception) throws IOException {
        writeProblem(response, HttpStatus.FORBIDDEN,
                ERROR_TYPE_BASE + "access-denied",
                "Access Denied",
                "You do not have permission to access this resource",
                request);
    }

    private void writeProblem(HttpServletResponse response, HttpStatus status, String type, String title,
                              String detail, HttpServletRequest request) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", request.getRequestURI());
        String traceId = MDC.get("correlationId");
        if (traceId == null) {
            traceId = request.getHeader("X-Correlation-ID");
        }
        if (traceId != null && !traceId.isBlank()) {
            body.put("traceId", traceId);
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
