---
description: Use when adding or changing an endpoint, a permission or a method anywhere behind the gateway, or when editing parkview.gateway.rules, GatewayProperties.Rule, RuleAuthorizer, Permissions or AuthorizationMatrixTest - the read/write keys, first match wins, deny by default, every change needs a matrix test.
paths:
  - "src/main/resources/application.yml"
  - "src/main/java/com/parkview/gateway/config/GatewayProperties.java"
  - "src/main/java/com/parkview/gateway/config/SecurityConfig.java"
  - "src/main/java/com/parkview/gateway/security/RuleAuthorizer.java"
  - "src/main/java/com/parkview/gateway/security/Permissions.java"
  - "src/test/java/com/parkview/gateway/security/AuthorizationMatrixTest.java"
  - "src/test/java/com/parkview/gateway/security/RoleBundles.java"
  - "src/test/java/com/parkview/gateway/security/PermissionsContractTest.java"
  - "src/test/java/com/parkview/gateway/config/GatewayPropertiesTest.java"
last-verified: 2026-11-26
---

# The rule matrix (`parkview.gateway.rules`)

The matrix is the heart of this service: it decides what reaches a service. A path no rule lists is refused whether a route exists or not (the chain ends in `denyAll()`).

## How a rule is written (`GatewayProperties.Rule`)

| Key | Meaning |
|---|---|
| `paths` | path patterns: `*` is one path segment, `/x/**` is `x` and everything below it |
| `methods` | optional; empty = all methods |
| `access` | `PUBLIC` (no token), `AUTHENTICATED` (any valid token), `PERMISSION`, `OPERATOR`, `ADMIN` (roles; no shipped rule uses them) |
| `permission: "x:y"` | one permission for the listed methods (`access: PERMISSION` is implied) |
| `read: "x:y"` / `write: "x:z"` | the permission for reads (`GET`, `HEAD`) and for writes (`POST`, `PUT`, `PATCH`, `DELETE`) of the same paths (ADR 0015). The two sets never overlap, so a pair needs no order |

- `read` / `write` cannot be combined with `methods`, `permission` or an `access` other than `PERMISSION`. A rule with only `read` or only `write` leaves the other methods to the rules below and in the end to the deny.
- `permission`, `read` and `write` must be in `Permissions.ALL`: an unknown or misspelt name fails startup (`GatewayPropertiesTest`).
- **First match wins** and a read/write pair is two rules in declaration order. Keep the order most specific first: `/sessions/search` before `/sessions/*`, `cameras/admin/**` before `cameras/*` (a camera id `admin`),
  `payments/webhook` before the rest, `/mapping/sites/*/rules` before the `/mapping/**` catch-all, the PUBLIC rules (language list, device endpoints, webhook) at the top. Read the surrounding comments before inserting a row.
- **No prefix rule for all methods.** Every endpoint of a service under a routed prefix is listed by method (fines, users, notifications, auth, payments). A `GET` rule also lists `HEAD`.
- `AUTHENTICATED` means "any valid token, the service decides on ownership and sites" (404 for what the caller may not see). Use it only when one permission cannot say it (owner-or-staff, "any of several permissions"), and say why in a comment.
  `PUBLIC` is only for the language list of identity-service (there are no sign-in endpoints behind the gateway: that is Keycloak), the Stripe webhook (signature), the camera device endpoints (api key) and `sessions/count/active`.
- `/actuator/**` is denied except health, info and the key-protected `prometheus`. The refusal is 401 without a token and problem+json 403 with one (`/internal/**`: CLAUDE.md §3).

## Checklist for a new or changed endpoint

1. The prefix has a route (`.claude/rules/routes-and-limits.md`); otherwise add it with its limits.
2. A rule row with the permission the standards and the catalogue name. The catalogue is `parkview-e2e/contracts/permissions.json` (edit, then `python contracts/generate_permissions.py`; never edit the `Permissions` block by hand, ADR 0002).
   A new permission also changes `RoleBundles` in the tests, which `PermissionsContractTest` keeps equal to `defaultsByRole` of the contract.
3. A test in `AuthorizationMatrixTest`: a permission-gated endpoint is a `Guarded(method, path, permission)` entry in `guarded()` (exactly that permission opens it, all others give 403, no token 401);
   an `AUTHENTICATED` or `PUBLIC` rule gets its own case, and so does anything that must stay denied (a method or path the service does not serve). Run it: it needs no Docker and no sockets.
4. The routing table and authorisation text in `README.md`, and an e2e case in `parkview-e2e` (standards section 9).
5. The service itself still checks role, permission and ownership (defence in depth): the gateway row never replaces the service's `@PreAuthorize` or `AccessPolicy`.

## What the matrix must keep true

- A read permission does not allow writes and a write permission does not imply its read (they are separate, ADR 0015); a token without a `permissions` claim: CLAUDE.md §3.
- Permission names are matched exactly (no prefix, case folding or wildcards). A rule names ONE permission: where the service wants two (the calibration scan: `camera:admin` and `ai:use`) the gateway asks for one and the service checks both.
- Roles and permissions of access management are separate: `access:read`, `access:manage` (assign roles and sites, invite) and `role:manage` (define roles under `/access/roles/**`, ADR 0016).

## When it does NOT apply

- Routes, filters, timeouts, rate limits and request sizes (`.claude/rules/routes-and-limits.md`).
- JWT validation, header stripping, CORS and error bodies (`.claude/rules/edge-security-and-errors.md`).
- A change inside a service that adds no endpoint, method or permission.
