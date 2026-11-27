package com.parkview.gateway.security;

import com.parkview.platform.security.PermissionClaims;
import com.parkview.platform.security.UpstreamUnavailableException;
import com.parkview.platform.security.Role;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Maps the claims of the identity provider's token to authorities (the claim parsing is {@code Role} and {@code PermissionClaims} of
 * parkview-security-core):
 * <ul>
 *   <li>{@code app_metadata.role} (admin | operator | driver) to {@code ROLE_*}; ADMIN implies OPERATOR
 *       (same hierarchy as in the services)</li>
 *   <li>{@code app_metadata.permissions} (string[]) to one authority per permission, named exactly like the
 *       permission ({@code zone:write}); entries that are not of the form {@code resource:action} are dropped</li>
 * </ul>
 * A token without the {@code permissions} claim has no permissions (its role authority still comes from {@code role}).
 */
public final class ParkviewJwtConverter {

    private ParkviewJwtConverter() {
    }

    /** The permissions are the claim of the token. */
    public static ReactiveJwtAuthenticationConverter create() {
        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities());
        return converter;
    }

    /**
     * The permissions are looked up for the subject of the token (ADR 0022 D3) and the claim is not read at all: a token that still carries it gains nothing from it.
     * A directory that cannot answer refuses the request ({@link PermissionsUnavailableAuthenticationException} with the outage as its cause, which the entry point answers with 503): never
     * "no permissions".
     */
    public static ReactiveJwtAuthenticationConverter create(ReactivePermissionDirectory directory) {
        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities(directory));
        return converter;
    }

    static Converter<Jwt, Flux<GrantedAuthority>> authorities() {
        return jwt -> {
            List<GrantedAuthority> result = new ArrayList<>(roles(jwt));
            result.addAll(permissions(jwt));
            return Flux.fromIterable(result);
        };
    }

    static Converter<Jwt, Flux<GrantedAuthority>> authorities(ReactivePermissionDirectory directory) {
        return jwt -> Flux.fromIterable(roles(jwt)).concatWith(directory.permissionsOf(jwt.getSubject())
                .flatMapMany(Flux::fromIterable)
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .onErrorMap(UpstreamUnavailableException.class,
                        e -> new PermissionsUnavailableAuthenticationException("The permissions of the caller cannot be determined", e)));
    }

    static List<GrantedAuthority> roles(Jwt jwt) {
        List<GrantedAuthority> result = new ArrayList<>();
        // an unknown or missing role gives no authority: the request is authenticated but not authorised for role rules
        Role.parse(appMetadata(jwt).get("role")).ifPresent(role -> {
            result.add(new SimpleGrantedAuthority(role.authority()));
            if (role == Role.ADMIN) {
                result.add(new SimpleGrantedAuthority(Role.OPERATOR.authority()));
            }
        });
        return result;
    }

    static List<GrantedAuthority> permissions(Jwt jwt) {
        // absent or malformed claim: no permissions (parsing and name rules are the library's)
        return PermissionClaims.parse(appMetadata(jwt).get(PermissionClaims.CLAIM)).stream()
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
    }

    private static Map<String, Object> appMetadata(Jwt jwt) {
        Map<String, Object> appMetadata = jwt.getClaimAsMap("app_metadata");
        return appMetadata == null ? Map.of() : appMetadata;
    }
}
