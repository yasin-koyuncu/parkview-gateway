package com.parkview.gateway.security;

import com.parkview.platform.security.PermissionsUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0022 D3 on the real security chain and the real rules: with {@code parkview.permissions.source=directory} the permissions of the caller come from the directory and not
 * from the token, and a directory that cannot answer is a 503, never a 403 and never "allowed". Runs on a mock server like the authorisation matrix (no sockets).
 */
@SpringBootTest(classes = AuthorizationTestApp.class, properties = {"SUPABASE_URL=http://issuer.test", "parkview.gateway.trusted-proxies=0",
        "parkview.permissions.source=directory"})
@Import(DirectoryAuthorizationTest.Directory.class)
class DirectoryAuthorizationTest {

    private static final String GUARDED = "/api/v1/zones";           // POST needs zone:write

    /** What the directory says about the caller of the test (the subject of every test token is user-1). */
    static final AtomicReference<Mono<Set<String>>> ANSWER = new AtomicReference<>(Mono.just(Set.of()));

    @TestConfiguration
    static class Directory {
        @Bean
        ReactivePermissionDirectory testDirectory() {
            return userId -> ANSWER.get();
        }
    }

    @Autowired
    ApplicationContext context;

    WebTestClient client;

    @BeforeEach
    void bind() {
        client = WebTestClient.bindToApplicationContext(context).build();
        ANSWER.set(Mono.just(Set.of()));
    }

    private static String tokenWithRoleOnly() {
        return AuthorizationTestApp.token(Map.of("role", "operator"));
    }

    @Test
    void thePermissionsOfTheDirectoryOpenThePathAndTheTokenNeedsNoClaim() {
        ANSWER.set(Mono.just(Set.of("zone:write")));

        client.post().uri(GUARDED).headers(h -> h.setBearerAuth(tokenWithRoleOnly())).exchange().expectStatus().isOk();
    }

    @Test
    void aPermissionTheDirectoryDoesNotGiveIsForbiddenWhateverTheTokenSays() {
        ANSWER.set(Mono.just(Set.of("session:read")));
        String claimsEverything = AuthorizationTestApp.token(Map.of("role", "admin", "permissions", List.of("zone:write", "access:manage", "fine:manage")));

        client.post().uri(GUARDED).headers(h -> h.setBearerAuth(claimsEverything)).exchange().expectStatus().isForbidden();
    }

    @Test
    void aChangeOfRoleIsInTheNextRequest() {
        ANSWER.set(Mono.just(Set.of()));
        client.post().uri(GUARDED).headers(h -> h.setBearerAuth(tokenWithRoleOnly())).exchange().expectStatus().isForbidden();

        ANSWER.set(Mono.just(Set.of("zone:write")));
        client.post().uri(GUARDED).headers(h -> h.setBearerAuth(tokenWithRoleOnly())).exchange().expectStatus().isOk();
    }

    @Test
    void aDirectoryThatCannotAnswerIsA503WithTheProblemCodeAndNotA401OrA403() {
        ANSWER.set(Mono.error(new PermissionsUnavailableException("down")));

        client.post().uri(GUARDED).headers(h -> h.setBearerAuth(tokenWithRoleOnly())).header("Accept-Language", "sv").exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().doesNotExist("WWW-Authenticate")
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.code").isEqualTo("error.upstreamUnavailable")
                .jsonPath("$.detail").isEqualTo("Tjänsten är inte tillgänglig just nu");
    }

    @Test
    void thePathsThatNeedNoPermissionDoNotAskTheDirectoryForTheCaller() {
        // an unauthenticated caller never reaches the converter: 401, the directory is not asked
        ANSWER.set(Mono.error(new PermissionsUnavailableException("must not be asked")));

        client.post().uri(GUARDED).exchange().expectStatus().isUnauthorized();
        assertThat(ANSWER.get()).isNotNull();
    }
}
