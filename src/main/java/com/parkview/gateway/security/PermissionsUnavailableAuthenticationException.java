package com.parkview.gateway.security;

import org.springframework.security.core.AuthenticationException;

/**
 * The permissions of the caller could not be looked up (the directory is down, ADR 0022 D3), raised by {@link ParkviewJwtConverter} inside the security chain, where only an
 * {@link AuthenticationException} is handled: the entry point of {@code ProblemAuthenticationHandlers} answers it with 503. It is deliberately not an
 * {@code AuthenticationServiceException}: Spring Security rethrows that one as an internal error (500) instead of handing it to the entry point.
 */
public class PermissionsUnavailableAuthenticationException extends AuthenticationException {

    public PermissionsUnavailableAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
