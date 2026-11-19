package dev.arthurgaspar.expeditions.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import dev.arthurgaspar.expeditions.platform.admission.AdmissionTokens;
import dev.arthurgaspar.expeditions.queue.QueueService.State;
import dev.arthurgaspar.expeditions.queue.QueueService.Status;

/** The Lua scripts against a real Redis. Skipped when Docker isn't available. */
@SpringBootTest(properties = {
        "queue.admitter-enabled=false",
        "queue.batch=3",
        "spring.kafka.listener.auto-startup=false",
        "expeditions.auth.mode=dev-headers",
        "expeditions.admission.secret=test-admission-secret-0123456789abcdef"})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class QueueRedisTest {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static class TestClock extends Clock {
        volatile Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }
    }

    private static final AtomicLong IDS = new AtomicLong(500);

    @Autowired
    QueueStore store;

    @Autowired
    QueueService queue;

    @Autowired
    AdmissionTokens tokens;

    @Autowired
    TestClock clock;

    @Autowired
    MockMvc mvc;

    private long expedition(int slots, Instant opensAt) {
        long id = IDS.incrementAndGet();
        store.register(id, opensAt, slots);
        return id;
    }

    private void admitOnce(long expeditionId) {
        store.admit(expeditionId, clock.instant(), 3, Duration.ofMinutes(2).toMillis());
    }

    @Test
    void firstComeFirstServedAndJoiningTwiceKeepsThePlace() {
        long exp = expedition(10, clock.instant());
        assertThat(queue.join("a", exp).position()).isEqualTo(1);
        assertThat(queue.join("b", exp).position()).isEqualTo(2);
        assertThat(queue.join("a", exp).position()).isEqualTo(1);
        Status c = queue.join("c", exp);
        assertThat(c.position()).isEqualTo(3);
        assertThat(c.waiting()).isEqualTo(3);
        assertThat(queue.status("nobody", exp).state()).isEqualTo(State.NOT_IN_LINE);
    }

    @Test
    void theWaitingRoomFillsBeforeOpeningButNobodyIsAdmitted() {
        long exp = expedition(10, clock.instant().plusSeconds(60));
        queue.join("early", exp);
        admitOnce(exp);
        assertThat(queue.status("early", exp).state()).isEqualTo(State.WAITING);
        clock.advance(Duration.ofSeconds(60));
        admitOnce(exp);
        assertThat(queue.status("early", exp).state()).isEqualTo(State.ADMITTED);
    }

    @Test
    void admitsNoMoreThanOpenSlotsAndTheBatch() {
        long exp = expedition(2, clock.instant());
        for (String a : new String[] {"a", "b", "c", "d"}) {
            queue.join(a, exp);
        }
        admitOnce(exp);
        assertThat(queue.status("a", exp).state()).isEqualTo(State.ADMITTED);
        assertThat(queue.status("b", exp).state()).isEqualTo(State.ADMITTED);
        assertThat(queue.status("c", exp).position()).isEqualTo(1);
        admitOnce(exp); // still two open slots, both covered by live admissions
        assertThat(queue.status("c", exp).state()).isEqualTo(State.WAITING);

        // a reserves: reservation reports one slot fewer, then that the admission was used
        store.capacity(exp, 1, 2);
        store.admissionUsed(exp, "a");
        admitOnce(exp);
        assertThat(queue.status("c", exp).state()).isEqualTo(State.WAITING);

        // a releases the slot again
        store.capacity(exp, 2, 3);
        admitOnce(exp);
        assertThat(queue.status("c", exp).state()).isEqualTo(State.ADMITTED);
        assertThat(queue.status("d", exp).position()).isEqualTo(1);

        long big = expedition(100, clock.instant());
        for (int i = 0; i < 5; i++) {
            queue.join("p" + i, big);
        }
        admitOnce(big);
        assertThat(queue.status("p3", big).position()).isEqualTo(1); // batch of 3
    }

    @Test
    void staleCapacityIsIgnored() {
        long exp = expedition(4, clock.instant());
        assertThat(store.capacity(exp, 3, 2)).isTrue();
        assertThat(store.capacity(exp, 4, 1)).isFalse();
        assertThat(store.capacity(exp, 3, 2)).isFalse();
        assertThat(store.capacity(exp, 0, 5)).isTrue();
        queue.join("x", exp);
        assertThat(queue.status("x", exp).soldOut()).isTrue();
        admitOnce(exp);
        assertThat(queue.status("x", exp).state()).isEqualTo(State.WAITING);
    }

    @Test
    void capacityArrivingBeforeTheExpeditionIsKept() {
        long id = IDS.incrementAndGet();
        store.capacity(id, 1, 7);
        store.register(id, clock.instant(), 40);
        queue.join("a", id);
        queue.join("b", id);
        admitOnce(id);
        assertThat(queue.status("a", id).state()).isEqualTo(State.ADMITTED);
        assertThat(queue.status("b", id).state()).isEqualTo(State.WAITING);
    }

    @Test
    void theAdmissionTokenIsValidUntilTheAdmissionLapses() {
        long exp = expedition(5, clock.instant());
        queue.join("mira", exp);
        admitOnce(exp);
        Status admitted = queue.status("mira", exp);
        assertThat(admitted.admittedUntil()).isEqualTo(clock.instant().plus(Duration.ofMinutes(2)));
        var admission = tokens.verify(admitted.admissionToken(), clock.instant());
        assertThat(admission.adventurerId()).isEqualTo("mira");
        assertThat(admission.expeditionId()).isEqualTo(exp);

        clock.advance(Duration.ofMinutes(2));
        assertThat(queue.status("mira", exp).state()).isEqualTo(State.NOT_IN_LINE);
        queue.join("teo", exp);
        assertThat(queue.join("mira", exp).position()).isEqualTo(2); // back of the line
    }

    @Test
    void overHttp() throws Exception {
        long exp = expedition(5, clock.instant());
        mvc.perform(post("/api/expeditions/" + exp + "/queue").header("X-Adventurer", "kai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("WAITING"))
                .andExpect(jsonPath("$.position").value(1));
        mvc.perform(get("/api/expeditions/999999/queue").header("X-Adventurer", "kai"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("expedition.not-found"));
        mvc.perform(get("/api/expeditions/" + exp + "/queue")).andExpect(status().isUnauthorized());
    }
}
