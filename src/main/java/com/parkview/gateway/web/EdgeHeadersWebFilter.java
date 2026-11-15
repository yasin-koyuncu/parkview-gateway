package com.parkview.gateway.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * First filter of the chain, before security:
 * <ul>
 *   <li>removes headers that only the gateway/platform may set (identity and internal-key headers), so a
 *       client can never impersonate a user or a service downstream</li>
 *   <li>assigns the request id ({@code X-Request-Id}; a client-supplied value is kept only if it is safe)
 *       and returns it to the caller</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EdgeHeadersWebFilter implements WebFilter {

    public static final String REQUEST_ID_ATTRIBUTE = "parkview.requestId";

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(PlatformHeaders.REQUEST_ID);
        String requestId = incoming != null && SAFE_ID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();

        boolean isActuator = exchange.getRequest().getPath().value().startsWith("/actuator/");

        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.headers(h -> {
                    PlatformHeaders.STRIPPED_AT_EDGE.stream()
                            // the gateway's own actuator endpoints authenticate callers with the internal key;
                            // those requests are never proxied, so the header cannot reach a service
                            .filter(name -> !(isActuator && name.equalsIgnoreCase(PlatformHeaders.INTERNAL_API_KEY)))
                            .forEach(h::remove);
                    h.set(PlatformHeaders.REQUEST_ID, requestId);
                }))
                .build();
        mutated.getAttributes().put(REQUEST_ID_ATTRIBUTE, requestId);
        mutated.getResponse().getHeaders().set(PlatformHeaders.REQUEST_ID, requestId);
        return chain.filter(mutated);
    }
}
