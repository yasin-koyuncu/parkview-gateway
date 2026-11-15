package com.parkview.gateway.web;

import io.netty.handler.timeout.ReadTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Turns gateway-level failures (no such route, upstream down, upstream too slow) into the platform's
 * problem+json instead of Spring's default error page, without leaking hostnames or stack traces. Title and detail are in the
 * language of the {@code Accept-Language} header (English without one or for an unknown language) and the problem carries the stable
 * {@code code} of the detail ({@code error.noRoute}, {@code error.upstreamUnavailable}, {@code error.upstreamTimeout},
 * {@code error.internal}, {@code error.http.<status>}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    private static final String UPSTREAM_UNAVAILABLE_KEY = "error.upstreamUnavailable";
    private static final String UPSTREAM_UNAVAILABLE = "The upstream service is currently unavailable";

    /** The statuses with a text of their own; every other status gets {@code title.http.<status>} / {@code error.http.<status>} (see {@link #problemFor}). */
    private static final Map<HttpStatus, GatewayProblem> KNOWN_PROBLEMS = Map.of(
            HttpStatus.NOT_FOUND, new GatewayProblem(
                    "title.noRoute", "Not found", "error.noRoute", "No route matches this request"),
            HttpStatus.BAD_GATEWAY, new GatewayProblem(
                    "title.badGateway", "Bad gateway", UPSTREAM_UNAVAILABLE_KEY, UPSTREAM_UNAVAILABLE),
            HttpStatus.SERVICE_UNAVAILABLE, new GatewayProblem(
                    "title.serviceUnavailable", "Service unavailable", UPSTREAM_UNAVAILABLE_KEY, UPSTREAM_UNAVAILABLE),
            HttpStatus.GATEWAY_TIMEOUT, new GatewayProblem(
                    "title.gatewayTimeout", "Gateway timeout", "error.upstreamTimeout", "The upstream service did not answer in time"));

    /**
     * What a failure in the cause chain means, tried in this order on every cause from the outermost: the status it carries, a
     * timeout is 504, a connection that failed or was closed early is 502. A classifier answers {@code null} for a cause it does not know.
     */
    private static final List<Function<Throwable, HttpStatusCode>> CLASSIFIERS = List.of(
            cause -> cause instanceof ResponseStatusException rse ? rse.getStatusCode() : null,
            cause -> cause instanceof TimeoutException || cause instanceof ReadTimeoutException ? HttpStatus.GATEWAY_TIMEOUT : null,
            cause -> cause instanceof ConnectException || isNamed(cause, "ConnectTimeout") || isNamed(cause, "PrematureClose")
                    ? HttpStatus.BAD_GATEWAY : null);

    private final ProblemWriter problems;

    public GatewayErrorHandler(ProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        HttpStatus http = HttpStatus.valueOf(statusFor(ex).value());

        if (http.is5xxServerError()) {
            log.warn("Gateway error {} for requestId={}: {}",
                    http.value(), exchange.getAttribute(EdgeHeadersWebFilter.REQUEST_ID_ATTRIBUTE), ex.toString());
        }
        return problems.write(exchange, http, problemFor(http));
    }

    private static GatewayProblem problemFor(HttpStatus status) {
        GatewayProblem known = KNOWN_PROBLEMS.get(status);
        if (known != null) {
            return known;
        }
        boolean serverError = status.is5xxServerError();
        return new GatewayProblem(
                "title.http." + status.value(), status.getReasonPhrase(),
                serverError ? "error.internal" : "error.http." + status.value(),
                serverError ? "An unexpected error occurred" : status.getReasonPhrase());
    }

    private static HttpStatusCode statusFor(Throwable ex) {
        return Stream.iterate(ex, Objects::nonNull, cause -> cause.getCause() == cause ? null : cause.getCause())
                .flatMap(cause -> CLASSIFIERS.stream().map(classifier -> classifier.apply(cause)).filter(Objects::nonNull))
                .findFirst()
                .orElse(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static boolean isNamed(Throwable cause, String fragment) {
        return cause.getClass().getName().contains(fragment);
    }
}
