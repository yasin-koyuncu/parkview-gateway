package com.parkview.gateway.config;

import com.parkview.gateway.security.Permissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Gateway behaviour that is configured rather than coded ({@code parkview.gateway.*}).
 *
 * @param rules          ordered authorisation rules; the first matching rule wins and
 *                       anything unmatched is denied (a path open to every signed-in user needs an explicit
 *                       {@code AUTHENTICATED} rule). The services enforce
 *                       roles and object ownership again (defence in depth); these rules keep
 *                       obviously unauthorised traffic away from them.
 * @param allowedOrigins browser origins allowed by CORS (the frontend)
 * @param sseTokenPaths  Ant patterns where a {@code ?token=} query parameter may carry the
 *                       bearer token (EventSource cannot set headers)
 * @param docsEnabled    expose the aggregated Swagger UI ({@code /swagger-ui.html}) - never in production
 * @param services       the downstream services by name: the one place their base URL is defined (the routes in
 *                       {@code application.yml} refer to it) and the source of the aggregated API docs routes and Swagger UI entries
 * @param trustedProxies required, no default (the infrastructure decides): the number of reverse proxies / load
 *                       balancers between the internet and this gateway. {@code 0} = nobody: {@code X-Forwarded-For}
 *                       is ignored and the rate-limit key is the socket address; {@code n >= 1} = the address
 *                       {@code n} hops from the right of {@code X-Forwarded-For} is the client. Too low and every
 *                       client shares the proxy's bucket; too high and a client can pick its own bucket.
 */
@Validated
@ConfigurationProperties("parkview.gateway")
public record GatewayProperties(
        @DefaultValue @Valid List<Rule> rules,
        @DefaultValue List<String> allowedOrigins,
        @DefaultValue List<String> sseTokenPaths,
        @DefaultValue("false") boolean docsEnabled,
        @DefaultValue Map<String, @Valid Service> services,
        Integer trustedProxies) {

    public GatewayProperties {
        if (trustedProxies == null || trustedProxies < 0) {
            throw new IllegalArgumentException("parkview.gateway.trusted-proxies (GATEWAY_TRUSTED_PROXIES) must be set to an integer >= 0: "
                    + "the number of reverse proxies in front of the gateway (0 = none, X-Forwarded-For is ignored); got " + trustedProxies);
        }
    }

    /**
     * A downstream service.
     *
     * @param url  base URL, e.g. {@code http://localhost:8085}
     * @param docs whether the service publishes OpenAPI docs that the gateway aggregates ({@code /docs/<name>/v3/api-docs})
     */
    public record Service(@NotBlank String url, @DefaultValue("true") boolean docs) {
    }

    public enum Access {
        PUBLIC,
        AUTHENTICATED,
        OPERATOR,
        ADMIN,
        /** the authority named by {@link Rule#permission()}, e.g. {@code zone:write} */
        PERMISSION
    }

    /**
     * One rule: the paths, optionally the methods, and what a caller needs. Three ways to say what is needed:
     * <ul>
     *   <li>{@code access: PUBLIC | AUTHENTICATED | OPERATOR | ADMIN};</li>
     *   <li>{@code permission: "zone:write"}: one permission for the methods listed (all methods without {@code methods});
     *       {@code access: PERMISSION} is implied, and may still be written out;</li>
     *   <li>{@code read:} and/or {@code write:}: the permission for the reads ({@code GET}, {@code HEAD}) and the one for the
     *       writes ({@code POST}, {@code PUT}, {@code PATCH}, {@code DELETE}) of the same paths. The two method sets are disjoint, so
     *       a pair needs no ordering. A rule with only one of them leaves the other methods to the rules below (and in the end to
     *       the deny). Not combinable with {@code methods}, {@code permission} or another access.</li>
     * </ul>
     * Every permission must be in the catalogue ({@link Permissions}); an unknown name fails the startup instead of silently
     * guarding nothing or locking everyone out.
     *
     * @param paths      Ant patterns
     * @param methods    HTTP methods the rule applies to; empty = all
     * @param access     required access level; {@code PERMISSION} is implied by {@code permission}, {@code read} or {@code write}
     * @param permission required permission of {@code access: PERMISSION}
     * @param read       permission for {@link #READ_METHODS}
     * @param write      permission for {@link #WRITE_METHODS}
     */
    public record Rule(
            @NotEmpty List<String> paths,
            @DefaultValue List<String> methods,
            Access access,
            String permission,
            String read,
            String write) {

        public static final List<String> READ_METHODS = List.of("GET", "HEAD");
        public static final List<String> WRITE_METHODS = List.of("POST", "PUT", "PATCH", "DELETE");

        public Rule {
            if (hasText(read) || hasText(write)) {
                if (hasText(permission)) {
                    throw new IllegalArgumentException("Rule " + paths + " sets read/write and permission '" + permission + "'; use one of them");
                }
                if (methods != null && !methods.isEmpty()) {
                    throw new IllegalArgumentException("Rule " + paths + " sets read/write and methods " + methods
                            + "; read covers " + READ_METHODS + " and write " + WRITE_METHODS);
                }
                if (access != null && access != Access.PERMISSION) {
                    throw new IllegalArgumentException("Rule " + paths + " sets read/write but access is " + access
                            + "; read and write mean access: PERMISSION");
                }
                requireKnown(paths, read, hasText(read));
                requireKnown(paths, write, hasText(write));
                access = Access.PERMISSION;
            } else {
                if (access == null && hasText(permission)) {
                    access = Access.PERMISSION;
                }
                if (access == null) {
                    throw new IllegalArgumentException("Rule " + paths + " needs access, permission, read or write");
                }
                if (access == Access.PERMISSION) {
                    requireKnown(paths, permission, true);
                } else if (hasText(permission)) {
                    throw new IllegalArgumentException("Rule " + paths + " sets permission '" + permission
                            + "' but access is " + access + "; use access: PERMISSION");
                }
            }
        }

        /**
         * The rule as plain rules (one {@code access}, one {@code permission}, the methods it applies to): itself, or for
         * {@code read:} / {@code write:} one rule per direction.
         */
        public List<Rule> expand() {
            if (!hasText(read) && !hasText(write)) {
                return List.of(this);
            }
            List<Rule> expanded = new ArrayList<>();
            if (hasText(read)) {
                expanded.add(new Rule(paths, READ_METHODS, Access.PERMISSION, read, null, null));
            }
            if (hasText(write)) {
                expanded.add(new Rule(paths, WRITE_METHODS, Access.PERMISSION, write, null, null));
            }
            return List.copyOf(expanded);
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }

        private static void requireKnown(List<String> paths, String permission, boolean present) {
            if (present && !Permissions.isKnown(permission)) {
                throw new IllegalArgumentException("Rule " + paths + " requires permission '" + permission
                        + "', which is not one of " + Permissions.sorted());
            }
        }
    }
}
