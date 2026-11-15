package com.parkview.gateway.config;

import org.springdoc.core.properties.AbstractSwaggerUiConfigProperties.SwaggerUrl;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Aggregated API documentation: {@code /docs/<service>/v3/api-docs} is routed to each service's own OpenAPI
 * endpoint, and the gateway's Swagger UI lists them all. Both come from {@code parkview.gateway.services} (the services
 * with {@code docs: true}), so a new service is added in one place. Only active with {@code DOCS_ENABLED=true}
 * ({@code parkview.gateway.docs-enabled}); never enable in production.
 */
@Configuration
@ConditionalOnProperty(prefix = "parkview.gateway", name = "docs-enabled", havingValue = "true")
public class DocsRoutesConfig {

    @Bean
    RouteLocator docsRoutes(RouteLocatorBuilder builder, GatewayProperties properties) {
        RouteLocatorBuilder.Builder routes = builder.routes();
        documented(properties).forEach((name, service) -> routes.route("docs-" + name, r -> r
                .path(docsPath(name), docsPath(name) + "/**")
                .filters(f -> f.rewritePath("/docs/" + name + "/(?<segment>.*)", "/${segment}"))
                .uri(service.url())));
        return routes.build();
    }

    /**
     * Fills the entries of the Swagger UI ({@code springdoc.swagger-ui.urls}) from the services before springdoc reads them; the
     * entries are not written in {@code application.yml}.
     */
    @Bean
    static BeanPostProcessor swaggerUrlsFromServices(ObjectProvider<GatewayProperties> properties) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof SwaggerUiConfigProperties swagger) {
                    Set<SwaggerUrl> urls = new LinkedHashSet<>();
                    documented(properties.getObject()).keySet()
                            .forEach(name -> urls.add(new SwaggerUrl(name, docsPath(name), null)));
                    swagger.setUrls(urls);
                }
                return bean;
            }
        };
    }

    private static String docsPath(String service) {
        return "/docs/" + service + "/v3/api-docs";
    }

    private static Map<String, GatewayProperties.Service> documented(GatewayProperties properties) {
        Map<String, GatewayProperties.Service> documented = new LinkedHashMap<>();
        properties.services().forEach((name, service) -> {
            if (service.docs()) {
                documented.put(name, service);
            }
        });
        return documented;
    }
}
