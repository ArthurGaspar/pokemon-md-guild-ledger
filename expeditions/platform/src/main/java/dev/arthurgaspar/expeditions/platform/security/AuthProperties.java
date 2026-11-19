package dev.arthurgaspar.expeditions.platform.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param mode             {@code jwt}: Keycloak bearer tokens. {@code dev-headers}: trust {@code X-Adventurer} and
 *                         {@code X-Roles}, for tests and running a service alone; never behind a public port
 * @param adventurerClaim  the token claim that identifies the adventurer
 * @param publicGetPaths   GET paths anyone may read (Ant patterns)
 * @param tokenQueryParam  also accept {@code ?access_token=}, for clients that can't set headers (EventSource)
 */
@ConfigurationProperties("expeditions.auth")
public record AuthProperties(@DefaultValue("jwt") Mode mode,
                             @DefaultValue("preferred_username") String adventurerClaim,
                             @DefaultValue List<String> publicGetPaths,
                             @DefaultValue("false") boolean tokenQueryParam) {

    public enum Mode { JWT, DEV_HEADERS }
}
