package com.parkview.gateway.config;

import com.parkview.gateway.security.InternalKeyAuthorization;
import com.parkview.gateway.security.ParkviewJwtConverter;
import com.parkview.gateway.security.RuleAuthorizer;
import com.parkview.gateway.security.SseTokenBearerConverter;
import com.parkview.gateway.web.ProblemAuthenticationHandlers;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy;
import org.springframework.web.cors.reactive.CorsConfigurationSource;

/**
 * Edge security: answer CORS before authorisation, write the security headers and error bodies, validate the JWT
 * (decoder: {@link JwtDecoderConfig}) and apply the declarative rules from {@code parkview.gateway.rules}
 * ({@link RuleAuthorizer}); a request no rule matches is denied.
 * Authorities: roles ADMIN (implies OPERATOR), OPERATOR, DRIVER from {@code app_metadata.role} and one authority per
 * entry of {@code app_metadata.permissions} (see {@link ParkviewJwtConverter}); rules use either.
 */
@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties({GatewayProperties.class, SecurityProperties.class})
public class SecurityConfig {

    private final GatewayProperties gateway;

    public SecurityConfig(GatewayProperties gateway) {
        this.gateway = gateway;
    }

    @Bean
    SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            ReactiveJwtDecoder jwtDecoder,
            CorsConfigurationSource cors,
            ProblemAuthenticationHandlers problems,
            InternalKeyAuthorization internalKey) {

        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .cors(c -> c.configurationSource(cors));
        writeHeaders(http);
        http.exceptionHandling(e -> e
                .authenticationEntryPoint(problems.entryPoint())
                .accessDeniedHandler(problems.accessDeniedHandler()));
        http.oauth2ResourceServer(o -> o
                .bearerTokenConverter(new SseTokenBearerConverter(gateway))
                .jwt(j -> j.jwtDecoder(jwtDecoder).jwtAuthenticationConverter(ParkviewJwtConverter.create())));
        authorizeExchanges(http, internalKey);
        return http.build();
    }

    private void writeHeaders(ServerHttpSecurity http) {
        String csp = gateway.docsEnabled()
                ? "default-src 'self' 'unsafe-inline' data:"
                : "default-src 'none'; frame-ancestors 'none'";
        http.headers(h -> h
                .contentSecurityPolicy(p -> p.policyDirectives(csp))
                .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER))
                .permissionsPolicy(p -> p.policy("camera=(), microphone=(), geolocation=()")));
    }

    private void authorizeExchanges(ServerHttpSecurity http, InternalKeyAuthorization internalKey) {
        http.authorizeExchange(ex -> {
            ex.pathMatchers(HttpMethod.OPTIONS, "/**").permitAll();
            ex.pathMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll();
            ex.pathMatchers("/actuator/prometheus").access(internalKey);
            ex.pathMatchers("/actuator/**").denyAll();

            if (gateway.docsEnabled()) {
                ex.pathMatchers("/docs/**", "/swagger-ui/**", "/swagger-ui.html", "/webjars/**", "/v3/api-docs/**").permitAll();
            }

            RuleAuthorizer.apply(ex, gateway.rules());
            // deny by default: a path no rule lists is refused (401 without a token, problem+json 403 with one), whether a route
            // exists for it or not. "Any valid token" is an explicit `access: AUTHENTICATED` rule, never the fallthrough.
            ex.anyExchange().denyAll();
        });
    }
}
