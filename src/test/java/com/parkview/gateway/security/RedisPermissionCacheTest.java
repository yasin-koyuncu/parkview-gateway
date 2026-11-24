package com.parkview.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The two lifetimes of the Redis cache and its behaviour when Redis fails: a miss or a lost write, never a failed request. */
@SuppressWarnings("unchecked")
class RedisPermissionCacheTest {

    private static final String USER = "7d5f0c1e-2b8a-4c55-9d63-0e1f2a3b4c5d";

    private final ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    private final ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
    private final RedisPermissionCache cache = new RedisPermissionCache(redis, Duration.ofSeconds(30), Duration.ofMinutes(5));

    RedisPermissionCacheTest() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void anAnswerIsKeptFor30SecondsAsFreshAndFiveMinutesAsStale() {
        when(values.set(any(), any(), any(Duration.class))).thenReturn(Mono.just(true));

        StepVerifier.create(cache.store(USER, Set.of("zone:write", "session:read"))).verifyComplete();

        verify(values).set("gateway:permissions:fresh:" + USER, "session:read,zone:write", Duration.ofSeconds(30));
        verify(values).set("gateway:permissions:stale:" + USER, "session:read,zone:write", Duration.ofMinutes(5));
    }

    @Test
    void anEmptyAnswerIsAValueOfItsOwn() {
        when(values.set(any(), any(), any(Duration.class))).thenReturn(Mono.just(true));
        when(values.get("gateway:permissions:fresh:" + USER)).thenReturn(Mono.just("-"));

        StepVerifier.create(cache.store(USER, Set.of())).verifyComplete();
        verify(values).set(eq("gateway:permissions:fresh:" + USER), eq("-"), eq(Duration.ofSeconds(30)));

        StepVerifier.create(cache.fresh(USER)).expectNext(Set.of()).verifyComplete();
    }

    @Test
    void theCopiesAreReadFromTheirOwnKeys() {
        when(values.get("gateway:permissions:fresh:" + USER)).thenReturn(Mono.just("session:read,zone:write"));
        when(values.get("gateway:permissions:stale:" + USER)).thenReturn(Mono.just("fine:read"));

        StepVerifier.create(cache.fresh(USER)).expectNext(Set.of("session:read", "zone:write")).verifyComplete();
        StepVerifier.create(cache.stale(USER)).expectNext(Set.of("fine:read")).verifyComplete();
    }

    @Test
    void aMissingKeyIsAMiss() {
        when(values.get(any())).thenReturn(Mono.empty());

        StepVerifier.create(cache.fresh(USER)).verifyComplete();
        StepVerifier.create(cache.stale(USER)).verifyComplete();
    }

    @Test
    void aRedisThatFailsIsAMissOnReadAndALostWriteNotAnError() {
        when(values.get(any())).thenReturn(Mono.error(new IllegalStateException("redis down")));
        when(values.set(any(), any(), any(Duration.class))).thenReturn(Mono.error(new IllegalStateException("redis down")));

        StepVerifier.create(cache.fresh(USER)).verifyComplete();
        StepVerifier.create(cache.stale(USER)).verifyComplete();
        StepVerifier.create(cache.store(USER, Set.of("zone:write"))).verifyComplete();
    }
}
