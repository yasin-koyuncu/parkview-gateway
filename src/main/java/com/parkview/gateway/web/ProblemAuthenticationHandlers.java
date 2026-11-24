package com.parkview.gateway.web;

import com.parkview.gateway.security.PermissionsUnavailableAuthenticationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * The 401 and 403 of the security chain as problem+json in the language of the caller ({@code Accept-Language}); the codes
 * are {@code error.unauthorized} and {@code error.forbidden}. A permission directory that is down is a 503 ({@code error.upstreamUnavailable}), not a 401.
 */
@Component
public class ProblemAuthenticationHandlers {

    private final ProblemWriter problems;

    public ProblemAuthenticationHandlers(ProblemWriter problems) {
        this.problems = problems;
    }

    public ServerAuthenticationEntryPoint entryPoint() {
        return (exchange, ex) -> ex instanceof PermissionsUnavailableAuthenticationException
                // the permissions of the caller could not be looked up: a 503, not a 401 (the caller did nothing wrong and must not sign in again)
                ? problems.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, GatewayProblem.UNAVAILABLE)
                : problems.write(exchange, HttpStatus.UNAUTHORIZED, GatewayProblem.UNAUTHORIZED);
    }

    public ServerAccessDeniedHandler accessDeniedHandler() {
        return (exchange, ex) -> problems.write(exchange, HttpStatus.FORBIDDEN, GatewayProblem.FORBIDDEN);
    }
}
