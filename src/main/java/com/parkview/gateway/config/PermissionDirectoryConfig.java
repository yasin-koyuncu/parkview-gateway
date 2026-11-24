package com.parkview.gateway.config;

import com.parkview.gateway.security.CachedPermissionDirectory;
import com.parkview.gateway.security.PermissionCache;
import com.parkview.gateway.security.ReactivePermissionDirectory;
import com.parkview.gateway.security.RedisPermissionCache;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * The permission directory of the gateway (ADR 0022 D3), only when {@code parkview.permissions.source=directory}: identity-service over HTTP with its internal key and the answers
 * cached in Redis. With the default {@code claim} none of this exists and the gateway reads the token as before.
 */
@Configuration
@ConditionalOnProperty(prefix = "parkview.permissions", name = "source", havingValue = "directory")
public class PermissionDirectoryConfig {

    private static final Logger log = LoggerFactory.getLogger(PermissionDirectoryConfig.class);

    static final String SERVICE = "auth-service";
    static final String INTERNAL_KEY_HEADER = "X-Internal-Api-Key";

    @Bean
    @ConditionalOnMissingBean(PermissionCache.class)
    PermissionCache permissionCache(ReactiveStringRedisTemplate redis, PermissionsProperties properties) {
        return new RedisPermissionCache(redis, properties.cacheTtl(), properties.staleLimit());
    }

    @Bean
    @ConditionalOnMissingBean(ReactivePermissionDirectory.class)
    ReactivePermissionDirectory reactivePermissionDirectory(WebClient.Builder builder, GatewayProperties gateway, PermissionsProperties properties,
                                                            PermissionCache cache) {
        GatewayProperties.Service auth = gateway.services().get(SERVICE);
        if (auth == null) {
            throw new IllegalStateException("parkview.permissions.source=directory needs parkview.gateway.services." + SERVICE + ".url: the directory is identity-service");
        }
        boolean keyed = properties.authInternalApiKey() != null && !properties.authInternalApiKey().isBlank();
        if (!keyed) {
            log.warn("parkview.permissions.auth-internal-api-key (AUTH_INTERNAL_API_KEY) is not set: lookups at the authentication-service fail, so every request that needs "
                    + "permissions is refused with 503");
        }
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.connectTimeout().toMillis())
                .responseTimeout(properties.readTimeout());
        WebClient client = builder.clone()
                .baseUrl(auth.url())
                .clientConnector(new ReactorClientHttpConnector(http))
                .filter(keyed
                        ? ExchangeFilterFunction.ofRequestProcessor(request -> Mono.just(
                                org.springframework.web.reactive.function.client.ClientRequest.from(request)
                                        .header(INTERNAL_KEY_HEADER, properties.authInternalApiKey()).build()))
                        : ExchangeFilterFunction.ofRequestProcessor(request -> Mono.error(new IllegalStateException("no key for the authentication-service"))))
                .build();
        return new CachedPermissionDirectory(client, cache);
    }
}
