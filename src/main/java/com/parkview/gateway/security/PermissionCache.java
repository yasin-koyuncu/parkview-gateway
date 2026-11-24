package com.parkview.gateway.security;

import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * Where {@link CachedPermissionDirectory} keeps the permissions it has read: a fresh copy (the cache time, 30 s) and a stale one (up to five minutes), the same two
 * lifetimes as {@code StaleCachedLookup} of the services. A cache that fails is a cache miss: the lookup then asks identity-service itself.
 */
public interface PermissionCache {

    /** The copy that is still fresh; empty when there is none. */
    Mono<Set<String>> fresh(String userId);

    /** The copy that is stale but still usable when identity-service does not answer; empty when there is none. */
    Mono<Set<String>> stale(String userId);

    /** Keeps the answer as the fresh and as the stale copy. */
    Mono<Void> store(String userId, Set<String> permissions);
}
