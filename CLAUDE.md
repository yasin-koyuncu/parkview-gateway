# parkview-gateway - Claude Code primary context

The single entry point of Park & View (Spring Cloud Gateway on WebFlux): validates the Supabase JWT, applies the declarative rule matrix in
`application.yml`, rate limits, size limits, timeouts and routes to the services. Browsers, drivers and cameras reach nothing else.
`AGENTS.md` is a thin pointer to this file for other agents.

## §1 Project & stack

| Pin | Value |
|---|---|
| Port / Java / Spring | `8080` (`PORT`) / **Java 24** (`java.version` overrides the parent's 21) / Spring Boot 3.5.3 through `parkview-parent`, Spring Cloud `2025.0.0`, WebFlux |
| platform modules | only `parkview-security-core` (`Role`, `PermissionClaims`, `InternalApiKeys`): no starter, no servlet module, no `parkview-domain`, no outbox (see `pom.xml`) |
| Data | none: no database, no Liquibase. Redis (reactive) holds the rate-limit buckets; the limiter fails open when Redis is down |
| Events | publishes none, consumes none |
| Routes | 17 routes to 11 services (`parkview.gateway.services`: auth, rule-engine, session, mapping, event-streaming, ingestion, file, notification, lookup, payment, ai) |
| Matrix | `parkview.gateway.rules` in `application.yml`: first match wins, anything unmatched is denied |

```
src/main/java/com/parkview/gateway/
  security/   RuleAuthorizer (applies the rules), Permissions (generated catalogue), ParkviewJwtConverter, AudienceValidator, InternalKeyAuthorization, SseTokenPaths/SseTokenBearerConverter, RoleHeader
  config/     SecurityConfig (chain, ends in denyAll), GatewayProperties (+ Rule record, validated at startup), JwtDecoderConfig, CorsConfig, RedisConfig, DocsRoutesConfig, TracingConfig
  filter/     SseTokenForwardFilter (-2), AuthForwardFilter (-1), AccessLogFilter
  web/        EdgeHeadersWebFilter + PlatformHeaders, GatewayErrorHandler, ProblemWriter, GatewayProblem, ProblemAuthenticationHandlers, RequestLocales, TraceContextEdgeDecorator
src/main/resources/   application.yml (route-templates, routes, rules), messages/errors.properties, messages/errors_sv.properties
```

## §2 Build & test

- `mvn -B -ntp verify` is the build and needs **JDK 24** (`~/.jdks/openjdk-24.0.2+12-54`; `java.version` is 24). **Never plain `mvn` here**: the default JDK is 8. Pass a central-only settings file, because the corporate mirror is unreachable.
- The platform library resolves from `~/.m2` (`mvn install` in `parkview-platform`) or from GitHub Packages (server id `github`, `docs/consuming.md` of the library). `mvn clean` can fail on a locked `target` on Windows: delete `target` by hand.
- `AuthorizationMatrixTest` runs the real rules on a mock server (no sockets, no Docker): it is the test to run after a matrix change. `GatewayRoutingTest` and `GatewayTracingTest` start the real gateway with a MockWebServer and a
  Redis Testcontainer (`redis:7`, or `TEST_REDIS_HOST`); they need Docker and Netty loopback sockets, which do not work on this machine. Say so instead of claiming they pass. Whole-system proof is `parkview-e2e`.
- The `Dockerfile` (`ARG JDK=24`, multi-stage, non-root `app`) needs the BuildKit secret `maven_settings`; no docker builds here.
- When asked to save credits, compile or typecheck only and say that nothing was run.

## §3 Invariants

1. **Every new or changed endpoint of any service needs a rule in the matrix** (and a route when it is under a new prefix) before the frontend can call it, a case in `AuthorizationMatrixTest`, and an e2e case. No prefix catch-all for all methods; list every endpoint by method. Details in `.claude/rules/rule-matrix.md`.
2. Deny by default: `SecurityConfig` ends in `denyAll()`, so a path no rule lists is refused. `/internal/**` is never routed and never gets a rule (the status codes and the `/actuator` exceptions: `.claude/rules/rule-matrix.md`).
3. The `Permissions` block between `BEGIN GENERATED permissions` and `END GENERATED permissions` is never edited by hand: change `contracts/permissions.json` in `parkview-e2e`, run `python contracts/generate_permissions.py`. A rule naming an unknown permission fails startup.
4. The gateway enforces role and permission only; sites and plates are the services' business (ADR 0011). A token without a `permissions` claim holds none, whatever its role. With `parkview.permissions.source=directory` (ADR 0022 D3) the permissions are asked of identity-service (`CachedPermissionDirectory`, Redis cache 30 s, stale 5 min, then 503) and the claim is not read at all; the outage is `PermissionsUnavailableAuthenticationException`, answered 503 by `SecurityConfig.resourceServerEntryPoint`.
5. Identity headers a client sends (`X-User-Id`, `X-User-Role`, `X-User-Email`, `X-Internal-Api-Key`) are stripped; the gateway uses the key of the callee (`AI_DETECTION_INTERNAL_API_KEY`), never its own. `?token=` is honoured on `parkview.gateway.sse-token-paths` only.
6. Rate-limit client address comes from `GATEWAY_TRUSTED_PROXIES` (required, no default), never the first `X-Forwarded-For` entry. No path, query or token in the access log.
7. WebFlux: no blocking calls, no servlet classes from the platform; errors are problem+json written by the gateway (`ProblemWriter`), texts in both `errors*.properties` files (`ErrorBundleParityTest`).
8. `application.yml` sits near SnakeYAML's alias limit and each service URL has one definition: read `.claude/rules/routes-and-limits.md` before editing routes.
9. Style: constructor injection, `@ConfigurationProperties` records, English identifiers and logs.

## §4 Process

- Branches: `master` = release, `develop` = development (checked out). Conventional commits (`feat:`, `fix:`, `refactor:`, `docs:`, `test:`, `chore:`); the body says why.
- **Commits are made by the user and are GPG-signed. Claude does not commit or push on its own.** Stage files explicitly, never `git add -A`.
- Never commit `.env`, `.idea` or `.claude/settings.local.json`.

## §5 Pointers

- Hub (siblings in `../parkview-e2e`): `docs/platform-standards.md` (section 2 security, 3 HTTP, 8 build and ops), `contracts/permissions.json`, `docs/reviews/review-gateway-fileservice.md`
  (package inventory, written during the library migration: check against the code).
- ADRs: 0002 and 0015 (permissions, read and write apart), 0004 (browser talks to the gateway only), 0009 (sites), 0011 (token content, internal keys), 0016 (roles), 0017 (public catalogue), 0019 (library).
- Library: `../parkview-platform/CLAUDE.md`. Service detail: `README.md` of this repo (routing table, authorisation rules).
- Path-scoped rules in `.claude/rules/`. Hooks and `.claude/settings.json` are synced from `parkview-e2e` (change them there).
