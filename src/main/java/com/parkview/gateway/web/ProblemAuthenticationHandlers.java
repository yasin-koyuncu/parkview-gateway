package com.parkview.gateway.web;

import org.springframework.http.HttpStatus;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * The 401 and 403 of the security chain as problem+json in the language of the caller ({@code Accept-Language}); the codes
 * are {@code error.unauthorized} and {@code error.forbidden}.
 */
@Component
public class ProblemAuthenticationHandlers {

    private final ProblemWriter problems;

    public ProblemAuthenticationHandlers(ProblemWriter problems) {
        this.problems = problems;
    }

    public ServerAuthenticationEntryPoint entryPoint() {
        return (exchange, ex) -> problems.write(exchange, HttpStatus.UNAUTHORIZED, GatewayProblem.UNAUTHORIZED);
    }

    public ServerAccessDeniedHandler accessDeniedHandler() {
        return (exchange, ex) -> problems.write(exchange, HttpStatus.FORBIDDEN, GatewayProblem.FORBIDDEN);
    }
}
