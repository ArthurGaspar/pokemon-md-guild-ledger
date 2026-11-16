package dev.arthurgaspar.expeditions.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** The HTTP client against a tiny local server: answers, 503s and a hang longer than the read timeout. */
class HttpDungeonSimTest {

    private HttpServer server;
    private final AtomicReference<String> behaviour = new AtomicReference<>("ok");
    private final AtomicReference<String> idempotencyKey = new AtomicReference<>();
    private HttpDungeonSim sim;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/api/runs", this::handle);
        server.start();
        sim = new HttpDungeonSim(RestClient.builder(), "http://localhost:" + server.getAddress().getPort(),
                Duration.ofSeconds(1), Duration.ofMillis(300));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        idempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
        exchange.getRequestBody().readAllBytes();
        switch (behaviour.get()) {
            case "503" -> respond(exchange, 503, "{\"detail\":\"overloaded\"}");
            case "hang" -> {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                respond(exchange, 200, "{}");
            }
            default -> respond(exchange, 200, """
                    {"runId":"r","outcome":"FAINTED","floors":20,"floorsCleared":7,"pokeFound":42}""");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void readsTheOutcomeAndSendsTheRunIdAsIdempotencyKey() {
        DungeonSim.Result result = sim.run("run-abc", 1, 2, List.of("a"));
        assertThat(result.outcome()).isEqualTo(DungeonSim.Outcome.FAINTED);
        assertThat(result.floorsCleared()).isEqualTo(7);
        assertThat(idempotencyKey.get()).isEqualTo("run-abc");
    }

    @Test
    void a503IsAServerError() {
        behaviour.set("503");
        assertThatThrownBy(() -> sim.run("run-abc", 1, 2, List.of("a"))).isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void aHangIsCutOffByTheReadTimeout() {
        behaviour.set("hang");
        long start = System.nanoTime();
        assertThatThrownBy(() -> sim.run("run-abc", 1, 2, List.of("a"))).isInstanceOf(ResourceAccessException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500));
    }
}
