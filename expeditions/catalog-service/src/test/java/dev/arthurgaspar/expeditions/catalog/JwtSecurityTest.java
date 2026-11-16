package dev.arthurgaspar.expeditions.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The real filter chain in {@code jwt} mode, with tokens shaped like Keycloak's ({@code realm_access.roles},
 * {@code preferred_username}) signed by a key made for the test instead of fetched from the realm.
 */
@SpringBootTest(properties = "expeditions.auth.mode=jwt")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JwtSecurityTest {

    static final KeyPair KEYS = rsa();
    static final KeyPair OTHER_KEYS = rsa();

    @TestConfiguration
    static class Keys {
        @Bean
        JwtDecoder jwtDecoder() {
            return NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private static KeyPair rsa() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String token(KeyPair keys, String username, String... roles) {
        NimbusJwtEncoder encoder = NimbusJwtEncoder.withKeyPair((RSAPublicKey) keys.getPublic(),
                (RSAPrivateKey) keys.getPrivate()).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("http://localhost:8180/realms/guild")
                .subject("3f1c-uuid-of-" + username)
                .claim("preferred_username", username)
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }

    private ResultActions publish(String code, String bearer) throws Exception {
        var request = post("/api/expeditions").contentType(MediaType.APPLICATION_JSON).content("""
                {"code":"%s","name":"Mt. Horn","dungeon":"mt-horn","difficulty":"C",
                 "opensAt":"2026-12-01T18:00:00Z","parties":2,"composition":{"TANK":1,"DAMAGE":1}}""".formatted(code));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request);
    }

    @Test
    void staffPublishAsThemselves() throws Exception {
        publish("jwt-staff-1", token(KEYS, "wigglytuff", "adventurer", "guild-staff")).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("select created_by from expeditions where code = 'jwt-staff-1'", String.class))
                .isEqualTo("wigglytuff");
    }

    @Test
    void adventurersCannotPublish() throws Exception {
        publish("jwt-adv-1", token(KEYS, "mira", "adventurer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.forbidden"));
    }

    @Test
    void missingForgedOrDevHeaderIdentitiesAreRejected() throws Exception {
        publish("jwt-none-1", null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("auth.required"));
        publish("jwt-forged-1", token(OTHER_KEYS, "wigglytuff", "guild-staff"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.invalid"));
        // the dev header means nothing in jwt mode
        mvc.perform(post("/api/expeditions").header("X-Adventurer", "wigglytuff").header("X-Roles", "guild-staff")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theCatalogIsPublic() throws Exception {
        mvc.perform(get("/api/expeditions")).andExpect(status().isOk());
    }
}
