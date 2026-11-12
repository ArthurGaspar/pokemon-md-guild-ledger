package dev.arthurgaspar.guildledger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityAndTimeTest {

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MutableClock testClock() {
            // millisecond-aligned: the database rounds timestamps to microseconds, which would blur the exact 7-day boundary
            return new MutableClock(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    MutableClock clock;

    private String register(String username) throws Exception {
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"displayName\":\"" + username + "\",\"password\":\"password-1\",\"speciesId\":25}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asString();
    }

    private JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String token,
                          int expected) throws Exception {
        String body = mvc.perform(req.header("Authorization", "Bearer " + token))
                .andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString();
        return body.isBlank() ? null : json.readTree(body);
    }

    @Test
    void tokensExpire() throws Exception {
        String token = register("timeout_" + System.nanoTime() % 100000);
        call(get("/api/me"), token, 200);
        // The decoder checks expiry against the system clock; a token issued 30 days ago has long expired.
        clock.advance(Duration.ofDays(-30));
        String stale = register("stale_" + System.nanoTime() % 100000);
        clock.advance(Duration.ofDays(30));
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + stale)).andExpect(status().isUnauthorized());
    }

    @Test
    void tokensSignedWithAnotherKeyAreRejected() throws Exception {
        var key = new SecretKeySpec("some-other-secret-some-other-secret!!".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        var encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
        Instant now = Instant.now();
        String forged = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().subject("1").issuedAt(now).expiresAt(now.plusSeconds(600)).build())).getTokenValue();
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + forged)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").header("Authorization", "Bearer not-a-jwt")).andExpect(status().isUnauthorized());
    }

    @Test
    void invitationsExpireAfterSevenDays() throws Exception {
        String suffix = Long.toString(System.nanoTime() % 100000);
        String leader = register("lead_" + suffix);
        String guest = register("guest_" + suffix);
        long team = call(post("/api/teams").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Expiry " + suffix + "\"}"), leader, 201).get("id").asLong();
        long inv = call(post("/api/teams/" + team + "/invitations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"guest_" + suffix + "\",\"role\":\"MEMBER\"}"), leader, 201).get("id").asLong();

        clock.advance(Duration.ofDays(7));

        mvc.perform(get("/api/invitations").header("Authorization", "Bearer " + guest))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/teams/" + team + "/invitations").header("Authorization", "Bearer " + leader))
                .andExpect(jsonPath("$[0].status").value("EXPIRED"));
        mvc.perform(post("/api/invitations/" + inv + "/accept").header("Authorization", "Bearer " + guest))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("invite.expired"));

        // an expired invitation doesn't block a new one
        call(post("/api/teams/" + team + "/invitations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"guest_" + suffix + "\",\"role\":\"MEMBER\"}"), leader, 201);
    }
}
