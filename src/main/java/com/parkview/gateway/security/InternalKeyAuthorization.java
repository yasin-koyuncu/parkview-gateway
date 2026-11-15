package com.parkview.gateway.security;

import com.parkview.gateway.config.SecurityProperties;
import com.parkview.gateway.web.PlatformHeaders;
import com.parkview.platform.security.InternalApiKeys;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.ReactiveAuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Metrics are for the platform only: callers must present the internal API key (compared in constant time, an unset key never
 * matches). Guards the gateway's own {@code /actuator/prometheus}.
 */
@Component
public class InternalKeyAuthorization implements ReactiveAuthorizationManager<AuthorizationContext> {

    private final InternalApiKeys keys;

    public InternalKeyAuthorization(SecurityProperties security) {
        this.keys = new InternalApiKeys(security.internalApiKey(), null, null);
    }

    @Override
    public Mono<AuthorizationDecision> check(Mono<Authentication> authentication, AuthorizationContext context) {
        String presented = context.getExchange().getRequest().getHeaders().getFirst(PlatformHeaders.INTERNAL_API_KEY);
        return Mono.just(new AuthorizationDecision(keys.check(presented) != InternalApiKeys.Result.NONE));
    }
}
