package com.parkview.gateway.security;

import com.parkview.platform.security.Role;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.Locale;

/** The value of the informational {@code X-User-Role} header: the highest role among the authorities. */
public final class RoleHeader {

    private RoleHeader() {
    }

    /** {@code admin} (implies operator), {@code operator}, otherwise {@code driver}. */
    public static String of(Collection<? extends GrantedAuthority> authorities) {
        if (has(authorities, Role.ADMIN)) {
            return value(Role.ADMIN);
        }
        return value(has(authorities, Role.OPERATOR) ? Role.OPERATOR : Role.DRIVER);
    }

    private static String value(Role role) {
        return role.name().toLowerCase(Locale.ROOT);
    }

    private static boolean has(Collection<? extends GrantedAuthority> authorities, Role role) {
        return authorities.stream().anyMatch(a -> role.authority().equals(a.getAuthority()));
    }
}
