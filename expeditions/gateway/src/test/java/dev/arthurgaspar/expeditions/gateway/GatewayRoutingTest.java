package dev.arthurgaspar.expeditions.gateway;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.sun.net.httpserver.HttpServer;

/**
 * Every route against a stand-in for each service, which answers with its own name, the path and the Authorization
 * header it received. Redis is absent on purpose: the rate limiter lets requests through when it can't reach it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.data.redis.port=1",
        "spring.data.redis.connect-timeout=200ms"})
@AutoConfigureWebTestClient
class GatewayRoutingTest {

    static final KeyPair KEYS = rsa();
    static final Map<String, HttpServer> SERVICES = Map.of(
            "catalog", stub("catalog"), "queue", stub("queue"), "reservation", stub("reservation"),
            "processing", stub("processing"), "issuance", stub("issuance"));

    @TestConfiguration
    static class Keys {
        @Bean
        ReactiveJwtDecoder jwtDecoder() {
            return NimbusReactiveJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
        }
    }

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry registry) {
        SERVICES.forEach((name, server) -> registry.add(name.toUpperCase() + "_URL",
                () -> "http://localhost:" + server.getAddress().getPort()));
    }

    @AfterAll
    static void stop() {
        SERVICES.values().forEach(s -> s.stop(0));
    }

    @Autowired
    WebTestClient http;

    private static HttpServer stub(String name) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                byte[] body = ("{\"service\":\"" + name + "\",\"path\":\"" + exchange.getRequestURI().getPath()
                        + "\",\"auth\":" + (auth == null ? "null" : "\"" + auth + "\"") + "}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String token(String username) {
        NimbusJwtEncoder encoder = NimbusJwtEncoder.withKeyPair((RSAPublicKey) KEYS.getPublic(),
                (RSAPrivateKey) KEYS.getPrivate()).build();
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(username).claim("preferred_username", username)
                .claim("realm_access", Map.of("roles", List.of("adventurer")))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }

    private void routes(String method, String path, String service) {
        String bearer = token("mira");
        http.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path)
                .header("Authorization", "Bearer " + bearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo(service)
                .jsonPath("$.path").isEqualTo(path)
                .jsonPath("$.auth").isEqualTo("Bearer " + bearer);
    }

    @Test
    void eachPathReachesItsService() {
        routes("POST", "/api/expeditions/7/queue", "queue");
        routes("GET", "/api/expeditions/7/queue", "queue");
        routes("POST", "/api/expeditions/7/reservations", "reservation");
        routes("GET", "/api/expeditions/7/capacity", "reservation");
        routes("GET", "/api/reservations", "reservation");
        routes("POST", "/api/reservations/3/confirm", "reservation");
        routes("GET", "/api/expeditions/7/parties/2/runs", "processing");
        routes("GET", "/api/runs/abc", "processing");
        routes("GET", "/api/circuit", "processing");
        routes("GET", "/api/certificates", "issuance");
        routes("GET", "/api/notifications", "issuance");
        routes("POST", "/api/expeditions", "catalog");
        routes("GET", "/api/expeditions/7", "catalog");
    }

    @Test
    void theCatalogIsPublicAndTheRestNeedsAToken() {
        http.get().uri("/api/expeditions").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("catalog");
        http.post().uri("/api/expeditions/7/queue").exchange().expectStatus().isUnauthorized();
        http.get().uri("/api/reservations").header("Authorization", "Bearer not-a-token")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void theNotificationStreamTakesTheTokenInTheQuery() {
        http.get().uri("/api/notifications/stream?access_token=" + token("mira")).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("issuance");
    }
}
