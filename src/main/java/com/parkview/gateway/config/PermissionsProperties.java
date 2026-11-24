package com.parkview.gateway.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Where the permissions of the caller come from ({@code parkview.permissions.*}, ADR 0022 D3), the same names as in the services.
 *
 * @param source              {@code claim} (the default): the claim {@code app_metadata.permissions} of the token, as before; {@code directory}: identity-service
 *                            ({@code GET /internal/v1/users/{id}/permissions}, cached in Redis), so the token carries no permissions and a change of role is effective
 *                            within {@code cacheTtl}
 * @param cacheTtl            how long an answer is served without asking again
 * @param staleLimit          how long an answer is kept for the case that identity-service does not answer; after that the request is refused (503)
 * @param authInternalApiKey  the key identity-service expects in {@code X-Internal-Api-Key} (its own key, env {@code AUTH_INTERNAL_API_KEY}); blank: no call is made and
 *                            the lookup fails closed. The base URL is {@code parkview.gateway.services.auth-service.url}
 * @param connectTimeout      connect timeout of the call
 * @param readTimeout         read timeout of the call
 */
@Validated
@ConfigurationProperties("parkview.permissions")
public record PermissionsProperties(
        @NotNull @DefaultValue("claim") Source source,
        @NotNull @DefaultValue("30s") Duration cacheTtl,
        @NotNull @DefaultValue("5m") Duration staleLimit,
        String authInternalApiKey,
        @NotNull @DefaultValue("2s") Duration connectTimeout,
        @NotNull @DefaultValue("3s") Duration readTimeout
) {

    public enum Source {
        CLAIM, DIRECTORY
    }

    public boolean fromDirectory() {
        return source == Source.DIRECTORY;
    }
}
