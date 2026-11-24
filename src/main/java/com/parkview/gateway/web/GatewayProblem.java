package com.parkview.gateway.web;

/**
 * An error the gateway writes itself: the keys of its title and detail in the error bundles
 * ({@code messages/errors*.properties}; the detail key is also the stable {@code code} of the response) and the English
 * texts that stay when a bundle has no entry.
 */
public record GatewayProblem(String titleKey, String title, String detailKey, String detail) {

    /** The security chain: no valid token. */
    public static final GatewayProblem UNAUTHORIZED = new GatewayProblem(
            "title.unauthorized", "Unauthorized", "error.unauthorized", "Authentication is required");

    /** The security chain: the permissions of the caller could not be looked up (the directory is down): not the caller's fault, refused and never allowed. */
    public static final GatewayProblem UNAVAILABLE = new GatewayProblem(
            "title.serviceUnavailable", "Service unavailable", "error.upstreamUnavailable", "The upstream service is currently unavailable");

    /** The security chain: a valid token that holds no right to the path. */
    public static final GatewayProblem FORBIDDEN = new GatewayProblem(
            "title.forbidden", "Forbidden", "error.forbidden", "You are not allowed to perform this action");
}
