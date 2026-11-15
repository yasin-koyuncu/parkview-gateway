package com.parkview.gateway.security;

import com.parkview.gateway.config.GatewayProperties;
import com.parkview.platform.security.Role;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity.AuthorizeExchangeSpec;

import java.util.List;

/** Applies the declarative {@code parkview.gateway.rules} to the authorisation chain, in declaration order. */
public final class RuleAuthorizer {

    private RuleAuthorizer() {
    }

    public static void apply(AuthorizeExchangeSpec exchanges, List<GatewayProperties.Rule> rules) {
        for (GatewayProperties.Rule declared : rules) {
            for (GatewayProperties.Rule rule : declared.expand()) {
                String[] paths = rule.paths().toArray(String[]::new);
                if (rule.methods().isEmpty()) {
                    require(rule, exchanges.pathMatchers(paths));
                } else {
                    for (String method : rule.methods()) {
                        require(rule, exchanges.pathMatchers(HttpMethod.valueOf(method), paths));
                    }
                }
            }
        }
    }

    private static void require(GatewayProperties.Rule rule, AuthorizeExchangeSpec.Access spec) {
        switch (rule.access()) {
            case PUBLIC -> spec.permitAll();
            case AUTHENTICATED -> spec.authenticated();
            case OPERATOR -> spec.hasRole(Role.OPERATOR.name());
            case ADMIN -> spec.hasRole(Role.ADMIN.name());
            case PERMISSION -> spec.hasAuthority(rule.permission());
        }
    }
}
