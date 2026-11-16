package dev.arthurgaspar.expeditions.platform.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

import dev.arthurgaspar.expeditions.platform.security.AuthProperties.Mode;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * One filter chain for every service: actuator and the configured public GETs are open, everything else needs an
 * adventurer. Roles are checked where they matter, with {@code @PreAuthorize}. Failures are problem details with a
 * stable code, like every other error.
 */
@AutoConfiguration(beforeName = {
        "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(AuthProperties.class)
@EnableMethodSecurity
public class SecurityAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SecurityAutoConfiguration.class);

    @Bean
    SecurityFilterChain expeditionsSecurity(HttpSecurity http, AuthProperties auth, JsonMapper json) throws Exception {
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> {
                    a.requestMatchers("/actuator/**").permitAll();
                    for (String path : auth.publicGetPaths()) {
                        a.requestMatchers(HttpMethod.GET, path).permitAll();
                    }
                    a.anyRequest().authenticated();
                })
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> problem(json, res, HttpStatus.UNAUTHORIZED,
                                "auth.required", "Sign in to do this"))
                        .accessDeniedHandler((req, res, ex) -> problem(json, res, HttpStatus.FORBIDDEN,
                                "auth.forbidden", "Your role doesn't allow this")));

        if (auth.mode() == Mode.DEV_HEADERS) {
            log.warn("expeditions.auth.mode=dev-headers: callers are trusted to say who they are. Tests and local runs only.");
            http.addFilterBefore(new DevHeadersFilter(), AnonymousAuthenticationFilter.class);
        } else {
            JwtAuthenticationConverter tokens = new JwtAuthenticationConverter();
            tokens.setJwtGrantedAuthoritiesConverter(new KeycloakRoles());
            tokens.setPrincipalClaimName(auth.adventurerClaim());
            DefaultBearerTokenResolver bearer = new DefaultBearerTokenResolver();
            bearer.setAllowUriQueryParameter(auth.tokenQueryParam());
            http.oauth2ResourceServer(o -> o
                    .bearerTokenResolver(bearer)
                    .jwt(j -> j.jwtAuthenticationConverter(tokens))
                    .authenticationEntryPoint((req, res, ex) -> problem(json, res, HttpStatus.UNAUTHORIZED,
                            "auth.invalid", "The token is missing, expired or not from this realm")));
        }
        return http.build();
    }

    private static void problem(JsonMapper json, HttpServletResponse res, HttpStatus status, String code, String detail)
            throws IOException {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("code", code);
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.getWriter().write(json.writeValueAsString(pd));
    }
}
