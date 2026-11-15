package com.parkview.gateway.config;

import org.junit.jupiter.api.Test;
import org.springdoc.core.properties.AbstractSwaggerUiConfigProperties.SwaggerUrl;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The Swagger UI entries come from {@code parkview.gateway.services}: one per service with {@code docs: true}, in declaration order. */
class DocsRoutesConfigTest {

    @Test
    @SuppressWarnings("unchecked")
    void swaggerUrlsAreGeneratedFromTheDocumentedServices() {
        Map<String, GatewayProperties.Service> services = new LinkedHashMap<>();
        services.put("session-service", new GatewayProperties.Service("http://localhost:8082", true));
        services.put("ingestion-service", new GatewayProperties.Service("http://localhost:9000", false));
        services.put("auth-service", new GatewayProperties.Service("http://localhost:8085", true));
        ObjectProvider<GatewayProperties> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(new GatewayProperties(List.of(), List.of(), List.of(), true, services, 0));

        BeanPostProcessor processor = DocsRoutesConfig.swaggerUrlsFromServices(provider);
        SwaggerUiConfigProperties swagger = new SwaggerUiConfigProperties();
        processor.postProcessBeforeInitialization(swagger, "swaggerUiConfigProperties");

        assertThat(swagger.getUrls()).extracting(SwaggerUrl::getName, SwaggerUrl::getUrl).containsExactly(
                tuple("session-service", "/docs/session-service/v3/api-docs"),
                tuple("auth-service", "/docs/auth-service/v3/api-docs"));
        // any other bean is left alone
        Object other = new Object();
        assertThat(processor.postProcessBeforeInitialization(other, "other")).isSameAs(other);
    }
}
