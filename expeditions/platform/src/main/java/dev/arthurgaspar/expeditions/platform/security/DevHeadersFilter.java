package dev.arthurgaspar.expeditions.platform.security;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@code dev-headers} mode: whoever sends {@code X-Adventurer} is that adventurer, with the roles in {@code X-Roles}.
 * It produces the same kind of authentication as a token does, so everything downstream is identical.
 */
public class DevHeadersFilter extends OncePerRequestFilter {

    public static final String ADVENTURER = "X-Adventurer";
    public static final String ROLES = "X-Roles";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = request.getHeader(ADVENTURER);
        if (id != null && !id.isBlank()) {
            String roles = request.getHeader(ROLES);
            List<GrantedAuthority> authorities = roles == null ? List.of() : Arrays.stream(roles.split(","))
                    .map(String::trim).filter(r -> !r.isEmpty())
                    .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
                    .toList();
            SecurityContextHolder.getContext()
                    .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(id, null, authorities));
        }
        chain.doFilter(request, response);
    }
}
