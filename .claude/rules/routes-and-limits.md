---
description: Use when adding or changing a route, a downstream service URL, a rate-limit tier, a request size limit, a timeout, the retry policy, a path rewrite, the SSE route or the generated docs routes in application.yml - first matching route wins, URLs are defined once, limits come from the route-templates.
paths:
  - "src/main/resources/application.yml"
  - "src/main/java/com/parkview/gateway/config/DocsRoutesConfig.java"
  - "src/main/java/com/parkview/gateway/config/RedisConfig.java"
  - "src/main/java/com/parkview/gateway/filter/**"
  - "src/test/java/com/parkview/gateway/GatewayRoutingTest.java"
  - "src/test/java/com/parkview/gateway/config/DocsRoutesConfigTest.java"
  - "src/test/java/com/parkview/gateway/config/RedisConfigTest.java"
last-verified: 2026-11-19
---

# Routes, limits and timeouts

Routing is separate from authorisation: a route without a rule in the matrix is unreachable (`.claude/rules/rule-matrix.md`), a rule without a route is a 404 from the gateway.

## Routes (`spring.cloud.gateway.server.webflux.routes`)

- The first matching route wins, so a narrower path is declared before the wider one: `auth-sensitive` before `auth-service`, `ingestion-calibration-scan` before `ingestion-service`, `payment-webhook` before `payment-service`.
- `uri` is always `${parkview.gateway.services.<name>.url}`. The base URL and its default port are defined once under `parkview.gateway.services` (name, `url`, `docs`). Adding a service is one line there, its route, and its rules.
  The names follow the environment variables (`RULE_ENGINE_URL`, `PARKING_MAPPING_URL`, `AUTH_SERVICE_URL`, ...); do not rename one without the compose files and the README table.
- Rate-limit tiers (`rate-credentials`, `-standard`, `-staff`, `-sessions`, `-expensive`), body limits (`body-16kb` ... `body-12mb`) and `retry-get` are written once in the `route-templates` block and used as `- *name`.
  SnakeYAML refuses more than 50 aliases per file and 32 are used: a route with a value of its own (4MB mapping, 22MB files, the SSE route) spells it out. A rate limit is one Redis bucket per route and client key,
  not shared between routes. A Redis call times out after `spring.data.redis.timeout: 500ms` (what the limiter then does: CLAUDE.md §1).
- Request size: the limit of a route must be at least what its service accepts (files 22 MB for the 20 MB image cap of file-service plus multipart overhead; camera snapshots 12 MB base64). Larger bodies get 413.
- Timeouts: connect 2 s, response 15 s. The SSE route (`event-streaming`) sets metadata `response-timeout: -1` and has no limiter; the calibration scan route sets `60000` (milliseconds) and is never retried.
  Only reads are retried (`retry-get`: `GET`, twice, on 502, 503, 504). Never add retries to a route that moves a camera, pays or writes.
- `RewritePath` keeps the optional slash inside the captured group (`/api/v1/cameras(?<segment>.*)` to `/cameras${segment}`), so the bare list path works; keep that when adding a rewrite (`cameras`, `detections`, `ai`).
- The AI route removes the client's `X-Internal-Api-Key` and adds the callee's key (CLAUDE.md §3). The event-streaming route removes `Origin` because the gateway answers CORS itself.
- With `DOCS_ENABLED=true` the Swagger UI entries and `/docs/<name>/v3/api-docs` routes are generated from `parkview.gateway.services` (`docs: false` for ingestion and ai); never enable it in production.
- Global filters: `SseTokenForwardFilter` (-2) moves `?token=` into the `Authorization` header on the SSE paths and removes it from the URL, `AuthForwardFilter` (-1) sets the informational `X-User-Id` and `X-User-Role`,
  `AccessLogFilter` logs route id, status, duration and user id, never paths or query strings.
- Rate-limit key: the authenticated user, otherwise the client address (the proxy setting: CLAUDE.md §3). `server.forward-headers-strategy` stays unset on purpose.

## Tests

`GatewayRoutingTest` (real gateway, MockWebServer, Redis container; needs Docker and Netty loopback), `DocsRoutesConfigTest`, `RedisConfigTest`. A new route needs a case in `GatewayRoutingTest` that shows the rewrite, limit or timeout.

## When it does NOT apply

- Who may call a path (`.claude/rules/rule-matrix.md`).
- Header stripping, JWT validation, CORS and error bodies (`.claude/rules/edge-security-and-errors.md`).
- Tracing configuration: `TracingConfig`, switched on only by `TRACING_ENABLED=true`.
