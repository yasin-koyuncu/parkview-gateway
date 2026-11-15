package com.parkview.gateway.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Writes RFC 7807 {@code application/problem+json} bodies with the same members the services use
 * ({@code timestamp}, {@code requestId}, {@code message}) so every error that reaches a client looks alike.
 * The texts of the gateway's own errors are in the language of the {@code Accept-Language} header and carry the stable
 * {@code code} of the text (see {@link GatewayProblem}).
 */
@Component
public class ProblemWriter {

    private final ObjectMapper mapper;
    private final MessageSource messages;

    public ProblemWriter(ObjectMapper mapper, @Qualifier("errorMessages") MessageSource messages) {
        this.mapper = mapper;
        this.messages = messages;
    }

    /**
     * Writes {@code problem} with the given status: title and detail in the language of the request ({@code problem}'s English
     * texts stay when the bundle has no entry), the key of the detail as the {@code code}.
     */
    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, GatewayProblem problem) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.empty();
        }
        Locale locale = RequestLocales.of(exchange);
        String detail = text(locale, problem.detailKey(), problem.detail());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", text(locale, problem.titleKey(), problem.title()));
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("timestamp", Instant.now().toString());
        Object requestId = exchange.getAttribute(EdgeHeadersWebFilter.REQUEST_ID_ATTRIBUTE);
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        body.put("message", detail);
        body.put("code", problem.detailKey());

        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            bytes = ("{\"status\":" + status.value() + "}").getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    /** The text of {@code key} in {@code locale}, or {@code fallback} (English) when there is no entry. */
    private String text(Locale locale, String key, String fallback) {
        String text = messages.getMessage(key, null, null, locale);
        return text != null ? text : fallback;
    }
}
