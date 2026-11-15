package com.parkview.gateway.web;

import com.parkview.platform.security.InternalApiKeys;

import java.util.List;

/**
 * The headers that only the platform may set. One list for what the gateway sets ({@code AuthForwardFilter},
 * {@code EdgeHeadersWebFilter}) and what it strips from a client's request, so a new identity header cannot be set
 * without being stripped.
 */
public final class PlatformHeaders {

    public static final String USER_ID = "X-User-Id";
    /** Never set by the gateway, but stripped: a client must not be able to present one. */
    public static final String USER_EMAIL = "X-User-Email";
    public static final String USER_ROLE = "X-User-Role";
    public static final String INTERNAL_API_KEY = InternalApiKeys.HEADER;
    public static final String REQUEST_ID = "X-Request-Id";

    /** Removed from every incoming request before anything else runs (the request id is replaced separately). */
    public static final List<String> STRIPPED_AT_EDGE = List.of(USER_ID, USER_EMAIL, USER_ROLE, INTERNAL_API_KEY);

    private PlatformHeaders() {
    }
}
