---
description: Use when changing JWT validation, the claim-to-authority converter, header stripping, the request id, the SSE token handling, CORS, the internal-key check of actuator, or the problem+json errors and their English and Swedish bundles - validate the token here again, strip what a client could spoof, never leak internals.
paths:
  - "src/main/java/com/parkview/gateway/security/**"
  - "src/main/java/com/parkview/gateway/web/**"
  - "src/main/java/com/parkview/gateway/config/JwtDecoderConfig.java"
  - "src/main/java/com/parkview/gateway/config/CorsConfig.java"
  - "src/main/java/com/parkview/gateway/config/SecurityProperties.java"
  - "src/main/java/com/parkview/gateway/config/TracingConfig.java"
  - "src/main/resources/messages/**"
  - "src/test/java/com/parkview/gateway/web/**"
  - "src/test/java/com/parkview/gateway/config/CorsConfigTest.java"
  - "src/test/java/com/parkview/gateway/config/TracingConfigurationTest.java"
  - "src/test/java/com/parkview/gateway/security/ParkviewJwtConverterTest.java"
  - "src/test/java/com/parkview/gateway/security/RoleHeaderTest.java"
  - "src/test/java/com/parkview/gateway/security/SseTokenBearerConverterTest.java"
  - "src/test/java/com/parkview/gateway/security/ErrorTextsSecurityTest.java"
last-verified: 2026-11-19
---

# Token, headers, CORS and errors at the edge

The rule matrix is `.claude/rules/rule-matrix.md`; this rule is everything else the gateway does to a request before and after it is routed.

## Token and claims

- Tokens are ES256 JWTs verified against `<SUPABASE_URL>/auth/v1/.well-known/jwks.json` (not `/auth/v1/keys`), issuer `${SUPABASE_URL}/auth/v1`, audience `authenticated` (`AudienceValidator`).
  `SUPABASE_URL` has no default: a missing value fails startup. The services validate the token again; the gateway is not the only gate.
- `ParkviewJwtConverter` reads only `app_metadata.role` (to `ROLE_*`, `ADMIN` implies `OPERATOR`) and `app_metadata.permissions` (one authority per `resource:action` entry, named exactly like the permission; other entries
  such as `ROLE_ADMIN` are dropped). Claim parsing is `Role` and `PermissionClaims` of `parkview-security-core`: fix those in the library, not here. No `sites` or `plates` claim exists (ADR 0011) and the gateway makes no lookup for them.
  No role fallback: a token without `permissions` holds none.

## Headers

- `EdgeHeadersWebFilter` runs first (highest precedence) and removes `X-User-Id`, `X-User-Role`, `X-User-Email` and `X-Internal-Api-Key` from every request (except the key on the gateway's own `/actuator/` paths, which are never proxied).
  The list is `PlatformHeaders.STRIPPED_AT_EDGE`: a new identity header is added there so it is set and stripped from one constant. `AuthForwardFilter` then sets `X-User-Id` and `X-User-Role` from the verified token as a convenience;
  services never use them for authorisation.
- The request id is kept from the client only when it matches `[A-Za-z0-9._-]{1,64}`, otherwise a UUID; it is returned in the response and forwarded.
- `/actuator/prometheus` takes the ops key (`OPS_API_KEY`, `InternalKeyAuthorization`); the gateway has no `INTERNAL_API_KEY` because no service calls it. An empty key disables the endpoint.
- Trace headers: a `traceparent` sent by the client is dropped unless `TRACING_TRUST_INCOMING=true` (only behind a trusted tracing proxy). Tracing is off unless `TRACING_ENABLED=true`: with it off there is no tracer, exporter or trace header.
- `?token=` is converted into an `Authorization` header only on `parkview.gateway.sse-token-paths` (`/api/v1/events/stream/**`) and removed from the URL before proxying (`SseTokenPaths`, `SseTokenBearerConverter`). Never widen that list.
- CORS is part of the security chain, so preflight works without a token; allowed origins are `FRONTEND_URL` (comma separated), no credentials or cookies.
- Never log a token, a path with a plate, a query string or a body.

## Errors

- The gateway writes its own errors as `application/problem+json` (`ProblemWriter`, `GatewayProblem`, `GatewayErrorHandler`, `ProblemAuthenticationHandlers`): 401, 403, no route, bad gateway, gateway timeout, service unavailable. No hostnames,
  upstream bodies or stack traces. Errors of the services pass through untouched; they are translated by the services.
- The `code` of a response is the detail key (`error.unauthorized`, `error.forbidden`, `error.noRoute`, `error.upstreamUnavailable`, `error.upstreamTimeout`, ...); a code never changes, the text may. Language is the `Accept-Language` header, English without it.
- `messages/errors.properties` (English base) and `errors_sv.properties`: every key exists in both with the same placeholders (`ErrorBundleParityTest`); a new `GatewayProblem` brings its keys in both files. Logs stay English.
- This is WebFlux: no blocking call in a filter or handler and no servlet class from the platform (`parkview-security-servlet` and `parkview-web` are deliberately not dependencies).

## When it does NOT apply

- Which path needs which permission (`.claude/rules/rule-matrix.md`) and route, limit and timeout settings (`.claude/rules/routes-and-limits.md`).
- Errors a service returns: they belong to that service.
