package com.parkview.gateway.security;

import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * The permissions of a user, looked up and not read from the access token (ADR 0022 D3): the reactive counterpart of {@code PermissionDirectory} of
 * parkview-security-core, which is blocking and so cannot be used on the event loop of the gateway. The gateway decides by permission before it routes, so it asks
 * identity-service (cached in Redis for the same 30 s as the services' own caches) when {@code parkview.permissions.source=directory}.
 */
public interface ReactivePermissionDirectory {

    /**
     * The current permissions of the user, each {@code resource:action}; empty when the user is unknown, has no role or the id is not a UUID.
     * Errors with {@link com.parkview.platform.security.PermissionsUnavailableException} when they cannot be determined: the request must be refused, never allowed.
     */
    Mono<Set<String>> permissionsOf(String userId);
}
