package com.parkview.gateway.security;

import com.parkview.platform.security.PermissionsUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR 0022 D3: the directory of the gateway. The HTTP answer is a stand-in (no socket), the cache an in-memory one. */
class CachedPermissionDirectoryTest {

    private static final String USER = "7d5f0c1e-2b8a-4c55-9d63-0e1f2a3b4c5d";

    private final List<URI> called = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final InMemoryCache cache = new InMemoryCache();
    private Supplier<Mono<ClientResponse>> answer = () -> json("{\"userId\":\"" + USER + "\",\"permissions\":[\"zone:write\",\"session:read\"]}");

    private final ExchangeFunction exchange = request -> {
        called.add(request.url());
        calls.incrementAndGet();
        return answer.get();
    };
    private final CachedPermissionDirectory directory = new CachedPermissionDirectory(WebClient.builder().baseUrl("http://auth").exchangeFunction(exchange).build(), cache);

    private static Mono<ClientResponse> json(String body) {
        return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body).build());
    }

    private static Mono<ClientResponse> status(HttpStatus status) {
        return Mono.just(ClientResponse.create(status).build());
    }

    @Test
    void asksIdentityServiceForThePermissionsOfTheUserAndKeepsTheAnswer() {
        StepVerifier.create(directory.permissionsOf(USER)).expectNext(Set.of("session:read", "zone:write")).verifyComplete();

        assertThat(called).extracting(URI::getPath).containsExactly("/internal/v1/users/" + USER + "/permissions");
        assertThat(cache.fresh.get(USER)).containsExactlyInAnyOrder("session:read", "zone:write");
        assertThat(cache.stale.get(USER)).containsExactlyInAnyOrder("session:read", "zone:write");
    }

    @Test
    void aFreshCopyIsServedWithoutACall() {
        cache.fresh.put(USER, Set.of("fine:read"));

        StepVerifier.create(directory.permissionsOf(USER)).expectNext(Set.of("fine:read")).verifyComplete();

        assertThat(calls).hasValue(0);
    }

    @Test
    void aUserWithoutARoleHasNoPermissionsAndThatIsCachedToo() {
        answer = () -> json("{\"userId\":\"" + USER + "\",\"permissions\":[]}");

        StepVerifier.create(directory.permissionsOf(USER)).expectNext(Set.of()).verifyComplete();

        assertThat(cache.fresh).containsKey(USER);
    }

    @Test
    void onlyResourceActionIsEverGrantedLikeForTheClaim() {
        answer = () -> json("{\"userId\":\"" + USER + "\",\"permissions\":[\"zone:write\",\"ROLE_ADMIN\",\"*\",\"\",\"Zone:Write\",\" session:read \"]}");

        StepVerifier.create(directory.permissionsOf(USER)).expectNext(Set.of("session:read", "zone:write")).verifyComplete();
    }

    @Test
    void aFailedCallUsesTheStaleCopy() {
        cache.stale.put(USER, Set.of("zone:write"));
        answer = () -> status(HttpStatus.SERVICE_UNAVAILABLE);

        StepVerifier.create(directory.permissionsOf(USER)).expectNext(Set.of("zone:write")).verifyComplete();
    }

    @Test
    void aFailedCallWithoutAnyCopyIsAnOutageAndNeverNoPermissions() {
        answer = () -> status(HttpStatus.UNAUTHORIZED);

        StepVerifier.create(directory.permissionsOf(USER)).expectError(PermissionsUnavailableException.class).verify();
    }

    @Test
    void aCallThatCannotBeMadeIsAnOutageToo() {
        answer = () -> Mono.error(new java.io.IOException("connection refused"));

        StepVerifier.create(directory.permissionsOf(USER)).expectError(PermissionsUnavailableException.class).verify();
    }

    @Test
    void aMissingOrNonUuidSubjectHasNoPermissionsAndNothingIsCalled() {
        StepVerifier.create(directory.permissionsOf(null)).expectNext(Set.of()).verifyComplete();
        StepVerifier.create(directory.permissionsOf("user-1")).expectNext(Set.of()).verifyComplete();

        assertThat(calls).hasValue(0);
    }

    /** A cache with two maps, as the Redis one has two keys. */
    static final class InMemoryCache implements PermissionCache {
        final Map<String, Set<String>> fresh = new HashMap<>();
        final Map<String, Set<String>> stale = new HashMap<>();

        @Override
        public Mono<Set<String>> fresh(String userId) {
            return Mono.justOrEmpty(fresh.get(userId));
        }

        @Override
        public Mono<Set<String>> stale(String userId) {
            return Mono.justOrEmpty(stale.get(userId));
        }

        @Override
        public Mono<Void> store(String userId, Set<String> permissions) {
            fresh.put(userId, permissions);
            stale.put(userId, permissions);
            return Mono.empty();
        }
    }
}
