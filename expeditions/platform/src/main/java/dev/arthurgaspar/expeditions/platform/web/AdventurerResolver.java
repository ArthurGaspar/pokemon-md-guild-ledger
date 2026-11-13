package dev.arthurgaspar.expeditions.platform.web;

import java.util.regex.Pattern;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Who is calling. Phase 1 trusts the {@code X-Adventurer} header; phase 3 replaces this with the subject of a
 * Keycloak token. Nothing else reads identity, so that swap stays in this class.
 */
public class AdventurerResolver implements HandlerMethodArgumentResolver {

    public static final String HEADER = "X-Adventurer";

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.@:-]{1,64}");

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == Adventurer.class;
    }

    @Override
    public Adventurer resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                      NativeWebRequest request, WebDataBinderFactory binderFactory) {
        String id = request.getHeader(HEADER);
        if (id == null || id.isBlank()) {
            throw ApiException.unauthorized("auth.required", "Send the " + HEADER + " header to identify yourself");
        }
        if (!ID.matcher(id).matches()) {
            throw ApiException.unauthorized("auth.invalid", HEADER + " must be 1-64 letters, digits or _.@:-");
        }
        return new Adventurer(id);
    }
}
