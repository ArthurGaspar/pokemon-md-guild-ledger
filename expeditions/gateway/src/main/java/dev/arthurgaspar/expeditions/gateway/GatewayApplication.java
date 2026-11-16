package dev.arthurgaspar.expeditions.gateway;

import java.security.Principal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Routes are in application.yml. The gateway checks tokens itself (so junk never reaches a service) and passes the
 * Authorization header on unchanged; each service checks it again.
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http) {
        // EventSource can't send headers, so the notification stream may carry ?access_token=
        ServerBearerTokenAuthenticationConverter bearer = new ServerBearerTokenAuthenticationConverter();
        bearer.setAllowUriQueryParameter(true);
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(a -> a
                        .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/expeditions", "/api/expeditions/*").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(o -> o.bearerTokenConverter(bearer).jwt(Customizer.withDefaults()))
                .build();
    }

    /** Rate limits are per adventurer (the token's subject), not per IP: a guild hall shares one address. */
    @Bean
    KeyResolver adventurerKey() {
        return exchange -> exchange.getPrincipal().map(Principal::getName);
    }
}
