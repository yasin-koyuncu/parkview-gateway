package com.parkview.gateway.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.parkview.platform.security.PermissionClaims;
import com.parkview.platform.security.PermissionsUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * {@link ReactivePermissionDirectory} that asks {@code GET /internal/v1/users/{userId}/permissions} of identity-service and keeps the answers in a {@link PermissionCache}.
 * A fresh copy is served without a call. When the call fails the stale copy is used (at most five minutes old, the limit of {@code StaleCachedLookup} in the services),
 * otherwise the lookup fails with {@link PermissionsUnavailableException}: an outage is not "no permissions". A warning with the user id only is logged, never the answer.
 */
public class CachedPermissionDirectory implements ReactivePermissionDirectory {

    private static final Logger log = LoggerFactory.getLogger(CachedPermissionDirectory.class);

    private final WebClient auth;
    private final PermissionCache cache;

    /** @param auth a client with the base URL, the timeouts and the internal key of identity-service */
    public CachedPermissionDirectory(WebClient auth, PermissionCache cache) {
        this.auth = auth;
        this.cache = cache;
    }

    @Override
    public Mono<Set<String>> permissionsOf(String userId) {
        if (!isUuid(userId)) {
            return Mono.just(Set.of());
        }
        return cache.fresh(userId).switchIfEmpty(Mono.defer(() -> fetch(userId)));
    }

    private Mono<Set<String>> fetch(String userId) {
        return auth.get().uri("/internal/v1/users/{userId}/permissions", userId)
                .retrieve()
                .bodyToMono(PermissionsView.class)
                .map(CachedPermissionDirectory::permissionsOf)
                .defaultIfEmpty(Set.of())
                .flatMap(permissions -> cache.store(userId, permissions).thenReturn(permissions))
                .onErrorResume(e -> {
                    log.warn("The permissions of user {} could not be read ({}), using the last answer if there is one", userId, e.getClass().getSimpleName());
                    return cache.stale(userId).switchIfEmpty(Mono.error(
                            new PermissionsUnavailableException("Permissions of the user cannot be determined", e)));
                });
    }

    private static Set<String> permissionsOf(PermissionsView view) {
        if (view.permissions() == null) {
            return Set.of();
        }
        // the same rule as for the claim: only resource:action is ever granted; sorted, so the set is stable
        return Collections.unmodifiableSortedSet(new TreeSet<>(PermissionClaims.parse(view.permissions())));
    }

    private static boolean isUuid(String id) {
        if (id == null) {
            return false;
        }
        try {
            UUID.fromString(id);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Response of identity-service {@code GET /internal/v1/users/{userId}/permissions}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PermissionsView(String userId, List<String> permissions) {
    }
}
