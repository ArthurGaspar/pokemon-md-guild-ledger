package dev.arthurgaspar.expeditions.platform.web;

import java.util.regex.Pattern;

import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Who is calling: the authenticated principal, from a Keycloak token's {@code preferred_username} or, in
 * {@code dev-headers} mode, from {@code X-Adventurer} (see platform/security).
 */
public class AdventurerResolver implements HandlerMethodArgumentResolver {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.@:-]{1,64}");

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == Adventurer.class;
    }

    @Override
    public Adventurer resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                      NativeWebRequest request, WebDataBinderFactory binderFactory) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            throw ApiException.unauthorized("auth.required", "Sign in to do this");
        }
        String id = auth.getName();
        if (id == null || !ID.matcher(id).matches()) {
            throw ApiException.unauthorized("auth.invalid", "An adventurer id is 1-64 letters, digits or _.@:-");
        }
        return new Adventurer(id);
    }
}
