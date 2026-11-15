package com.parkview.gateway.config;

import com.parkview.gateway.config.GatewayProperties.Access;
import com.parkview.gateway.security.Permissions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The declarative rules: permission rules are validated when the properties bind, i.e. at startup. */
class GatewayPropertiesTest {

    private static GatewayProperties bind(Map<String, Object> properties) {
        MutablePropertySources sources = new MutablePropertySources();
        Map<String, Object> all = new HashMap<>(Map.of("parkview.gateway.trusted-proxies", "0"));
        all.putAll(properties);
        sources.addFirst(new MapPropertySource("test", all));
        return new Binder(ConfigurationPropertySources.from(sources)).bind("parkview.gateway", GatewayProperties.class).get();
    }

    private static Map<String, Object> rule(String access, String permission) {
        Map<String, Object> props = new HashMap<>(Map.of(
                "parkview.gateway.rules[0].paths[0]", "/api/v1/x/**",
                "parkview.gateway.rules[0].access", access));
        if (permission != null) {
            props.put("parkview.gateway.rules[0].permission", permission);
        }
        return props;
    }

    @Test
    void aPermissionRuleBindsWithItsPermission() {
        GatewayProperties.Rule rule = bind(rule("PERMISSION", "zone:write")).rules().get(0);
        assertThat(rule.access()).isEqualTo(Access.PERMISSION);
        assertThat(rule.permission()).isEqualTo("zone:write");
    }

    @Test
    void existingRoleRulesKeepWorkingWithoutAPermission() {
        assertThat(bind(rule("OPERATOR", null)).rules().get(0).permission()).isNull();
        assertThat(bind(rule("PUBLIC", null)).rules().get(0).access()).isEqualTo(Access.PUBLIC);
    }

    @Test
    void anUnknownPermissionFailsTheStartup() {
        assertThatThrownBy(() -> bind(rule("PERMISSION", "zone:wrtie")))
                .isInstanceOf(BindException.class)
                .hasStackTraceContaining("zone:wrtie");
    }

    @Test
    void aPermissionRuleWithoutAPermissionOrAPermissionOnAnotherAccessTypeFails() {
        assertThatThrownBy(() -> bind(rule("PERMISSION", null))).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(rule("OPERATOR", "ai:use"))).isInstanceOf(BindException.class);
    }

    private static Map<String, Object> rule(Map<String, String> fields) {
        Map<String, Object> props = new HashMap<>(Map.of("parkview.gateway.rules[0].paths[0]", "/api/v1/x/**"));
        fields.forEach((key, value) -> props.put("parkview.gateway.rules[0]." + key, value));
        return props;
    }

    @Test
    void aPermissionImpliesAccessPermission() {
        GatewayProperties.Rule rule = bind(rule(Map.of("permission", "zone:write"))).rules().get(0);
        assertThat(rule.access()).isEqualTo(Access.PERMISSION);
        assertThat(rule.permission()).isEqualTo("zone:write");
        assertThat(rule.expand()).containsExactly(rule);
        // writing the access out stays valid
        assertThat(bind(rule(Map.of("access", "PERMISSION", "permission", "zone:write"))).rules().get(0)).isEqualTo(rule);
    }

    @Test
    void readAndWriteExpandToTheReadMethodsAndTheWriteMethods() {
        GatewayProperties.Rule rule = bind(rule(Map.of("read", "permit:read", "write", "permit:manage"))).rules().get(0);

        assertThat(rule.access()).isEqualTo(Access.PERMISSION);
        assertThat(rule.expand()).hasSize(2);
        GatewayProperties.Rule reads = rule.expand().get(0);
        GatewayProperties.Rule writes = rule.expand().get(1);
        assertThat(reads.methods()).containsExactly("GET", "HEAD");
        assertThat(reads.permission()).isEqualTo("permit:read");
        assertThat(writes.methods()).containsExactly("POST", "PUT", "PATCH", "DELETE");
        assertThat(writes.permission()).isEqualTo("permit:manage");
        assertThat(reads.paths()).isEqualTo(rule.paths());
        assertThat(writes.paths()).isEqualTo(rule.paths());
        assertThat(reads.methods()).doesNotContainAnyElementsOf(writes.methods());
    }

    @Test
    void readAloneOrWriteAloneIsOneRule() {
        List<GatewayProperties.Rule> reads = bind(rule(Map.of("read", "zone:read"))).rules().get(0).expand();
        assertThat(reads).singleElement().satisfies(r -> {
            assertThat(r.methods()).isEqualTo(GatewayProperties.Rule.READ_METHODS);
            assertThat(r.permission()).isEqualTo("zone:read");
        });
        List<GatewayProperties.Rule> writes = bind(rule(Map.of("write", "zone:write"))).rules().get(0).expand();
        assertThat(writes).singleElement().satisfies(r -> {
            assertThat(r.methods()).isEqualTo(GatewayProperties.Rule.WRITE_METHODS);
            assertThat(r.permission()).isEqualTo("zone:write");
        });
    }

    @Test
    void readAndWriteMustNameKnownPermissionsAndCannotBeCombinedWithTheOtherForms() {
        assertThatThrownBy(() -> bind(rule(Map.of("read", "zone:raed")))).isInstanceOf(BindException.class).hasStackTraceContaining("zone:raed");
        assertThatThrownBy(() -> bind(rule(Map.of("read", "zone:read", "write", "zone:wrtie")))).isInstanceOf(BindException.class)
                .hasStackTraceContaining("zone:wrtie");
        assertThatThrownBy(() -> bind(rule(Map.of("read", "zone:read", "permission", "zone:write")))).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(rule(Map.of("write", "zone:write", "access", "AUTHENTICATED")))).isInstanceOf(BindException.class);
        Map<String, Object> withMethods = rule(Map.of("read", "zone:read"));
        withMethods.put("parkview.gateway.rules[0].methods[0]", "GET");
        assertThatThrownBy(() -> bind(withMethods)).isInstanceOf(BindException.class).hasStackTraceContaining("methods");
    }

    @Test
    void aRuleThatSaysNothingAboutWhatItNeedsFails() {
        assertThatThrownBy(() -> bind(rule(Map.of()))).isInstanceOf(BindException.class)
                .hasStackTraceContaining("needs access, permission, read or write");
    }

    @Test
    void trustedProxiesIsRequiredAndNotNegative() {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("test", Map.of("parkview.gateway.rules[0].paths[0]", "/x/**", "parkview.gateway.rules[0].access", "PUBLIC")));
        assertThatThrownBy(() -> new Binder(ConfigurationPropertySources.from(sources)).bind("parkview.gateway", GatewayProperties.class).get())
                .isInstanceOf(BindException.class)
                .hasStackTraceContaining("GATEWAY_TRUSTED_PROXIES");
        assertThatThrownBy(() -> bind(Map.of("parkview.gateway.trusted-proxies", "-1")))
                .isInstanceOf(BindException.class)
                .hasStackTraceContaining("GATEWAY_TRUSTED_PROXIES");
        assertThat(bind(Map.of("parkview.gateway.trusted-proxies", "2")).trustedProxies()).isEqualTo(2);
    }

    @Test
    void theShippedApplicationYmlBindsAndEveryRuleNamesAKnownPermission() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("env", Map.of("GATEWAY_TRUSTED_PROXIES", "1")));
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml")).forEach(sources::addLast);
        GatewayProperties props = new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources))
                .bind("parkview.gateway", GatewayProperties.class).get();

        assertThat(props.trustedProxies()).as("comes from GATEWAY_TRUSTED_PROXIES, no default in the yml").isEqualTo(1);

        List<GatewayProperties.Rule> permissionRules = props.rules().stream().flatMap(r -> r.expand().stream())
                .filter(r -> r.access() == Access.PERMISSION).toList();
        assertThat(permissionRules).isNotEmpty().allSatisfy(r -> assertThat(Permissions.isKnown(r.permission())).isTrue());
        // the shipped file uses the read/write pairs
        assertThat(props.rules()).anyMatch(r -> r.read() != null && r.write() != null);
        // the prefix catch-alls for all methods are gone: a rule without methods is PERMISSION or lists everything it serves
        assertThat(props.rules()).filteredOn(r -> r.methods().isEmpty() && r.read() == null && r.write() == null)
                .extracting(GatewayProperties.Rule::paths)
                .allSatisfy(paths -> assertThat(paths).noneMatch(path -> path.matches("/api/v1/(fines|users|notifications|auth)/\\*\\*")));
        // the operator/admin functions are all migrated to permissions
        assertThat(props.rules()).noneMatch(r -> r.access() == Access.OPERATOR || r.access() == Access.ADMIN);
    }

    @Test
    void everyRouteUsesTheUrlOfAServiceDefinedOnceAndEveryServiceHasAUrl() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml")).forEach(sources::addLast);
        Map<String, Object> all = new HashMap<>();
        sources.forEach(source -> ((MapPropertySource) source).getSource().forEach(all::put));

        Pattern placeholder = Pattern.compile("\\$\\{parkview\\.gateway\\.services\\.([a-z-]+)\\.url}");
        List<String> routeUris = all.entrySet().stream()
                .filter(e -> e.getKey().matches("spring\\.cloud\\.gateway\\.server\\.webflux\\.routes\\[\\d+]\\.uri"))
                .map(e -> String.valueOf(e.getValue())).toList();
        assertThat(routeUris).hasSizeGreaterThan(10);
        for (String uri : routeUris) {
            Matcher matcher = placeholder.matcher(uri);
            assertThat(matcher.matches()).as("route uri " + uri + " refers to parkview.gateway.services").isTrue();
            assertThat(all).as("service " + matcher.group(1)).containsKey("parkview.gateway.services." + matcher.group(1) + ".url");
        }
    }
}
