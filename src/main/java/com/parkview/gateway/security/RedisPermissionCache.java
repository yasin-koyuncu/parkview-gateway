package com.parkview.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@link PermissionCache} in Redis, shared by the instances of the gateway so a change of role is seen by all of them within the cache time. The value is the sorted
 * list of the permissions joined with a comma (a permission is {@code resource:action}, never a comma), the empty list is a value of its own (a user without a role is
 * cached too). Every Redis error is a miss or a lost write, never a failed request.
 */
public class RedisPermissionCache implements PermissionCache {

    private static final Logger log = LoggerFactory.getLogger(RedisPermissionCache.class);

    static final String FRESH_PREFIX = "gateway:permissions:fresh:";
    static final String STALE_PREFIX = "gateway:permissions:stale:";
    /** Marks an empty answer: Redis has no empty string that is distinct from a missing key for this purpose. */
    static final String NONE = "-";

    private final ReactiveStringRedisTemplate redis;
    private final Duration ttl;
    private final Duration staleLimit;

    public RedisPermissionCache(ReactiveStringRedisTemplate redis, Duration ttl, Duration staleLimit) {
        this.redis = redis;
        this.ttl = ttl;
        this.staleLimit = staleLimit;
    }

    @Override
    public Mono<Set<String>> fresh(String userId) {
        return read(FRESH_PREFIX + userId);
    }

    @Override
    public Mono<Set<String>> stale(String userId) {
        return read(STALE_PREFIX + userId);
    }

    @Override
    public Mono<Void> store(String userId, Set<String> permissions) {
        String value = permissions.isEmpty() ? NONE : String.join(",", new TreeSet<>(permissions));
        return redis.opsForValue().set(FRESH_PREFIX + userId, value, ttl)
                .then(redis.opsForValue().set(STALE_PREFIX + userId, value, staleLimit))
                .then()
                .onErrorResume(e -> {
                    log.warn("The permission cache could not be written ({})", e.getClass().getSimpleName());
                    return Mono.empty();
                });
    }

    private Mono<Set<String>> read(String key) {
        return redis.opsForValue().get(key)
                .map(RedisPermissionCache::parse)
                .onErrorResume(e -> {
                    log.warn("The permission cache could not be read ({})", e.getClass().getSimpleName());
                    return Mono.empty();
                });
    }

    private static Set<String> parse(String value) {
        if (NONE.equals(value) || value.isBlank()) {
            return Set.of();
        }
        return Collections.unmodifiableSortedSet(new TreeSet<>(Arrays.asList(value.split(","))));
    }
}
