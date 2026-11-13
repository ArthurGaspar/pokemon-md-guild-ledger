package dev.arthurgaspar.expeditions.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import dev.arthurgaspar.expeditions.platform.Role;
import dev.arthurgaspar.expeditions.platform.admission.AdmissionTokens;
import dev.arthurgaspar.expeditions.platform.admission.AdmissionTokens.Admission;
import dev.arthurgaspar.expeditions.platform.events.EventCodec;
import dev.arthurgaspar.expeditions.platform.events.Events;
import dev.arthurgaspar.expeditions.platform.events.Events.ExpeditionPublished;
import dev.arthurgaspar.expeditions.platform.events.Events.PartyLocked;
import dev.arthurgaspar.expeditions.platform.events.Events.PartyReverted;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The saga through the HTTP API, with time driven by a {@link MutableClock}. Run on H2 and on PostgreSQL. */
@Import(ReservationScenarios.Clocks.class)
abstract class ReservationScenarios {

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MutableClock testClock() {
            // the database keeps microseconds; whole seconds keep deadline comparisons exact
            return new MutableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        }
    }

    static final Map<Role, Integer> PARTY = Map.of(Role.TANK, 1, Role.HEALER, 1, Role.DAMAGE, 2);

    private static final AtomicLong IDS = new AtomicLong(100);

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    MutableClock clock;

    @Autowired
    ReservationService service;

    @Autowired
    AdmissionTokens tokens;

    @Autowired
    EventCodec codec;

    @Autowired
    JdbcTemplate jdbc;

    long expedition(int parties, Map<Role, Integer> composition, Instant opensAt) {
        long id = IDS.incrementAndGet();
        service.register(new ExpeditionPublished(id, "exp-" + id, "Expedition " + id, "sky-peak", "STAR",
                opensAt, parties, composition));
        return id;
    }

    long openExpedition(int parties) {
        return expedition(parties, PARTY, clock.instant().minusSeconds(60));
    }

    String token(long expeditionId, String adventurer) {
        return tokens.issue(new Admission(expeditionId, adventurer, clock.instant().plusSeconds(120)));
    }

    ResultActions tryHold(String adventurer, long expeditionId, Role role, String token) throws Exception {
        return mvc.perform(post("/api/expeditions/" + expeditionId + "/reservations").header("X-Adventurer", adventurer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"" + role + "\",\"admissionToken\":\"" + token + "\"}"));
    }

    long hold(String adventurer, long expeditionId, Role role) throws Exception {
        String body = tryHold(adventurer, expeditionId, role, token(expeditionId, adventurer))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asLong();
    }

    ResultActions confirm(String adventurer, long reservationId) throws Exception {
        return mvc.perform(post("/api/reservations/" + reservationId + "/confirm").header("X-Adventurer", adventurer));
    }

    JsonNode reservation(String adventurer, long reservationId) throws Exception {
        String body = mvc.perform(get("/api/reservations/" + reservationId).header("X-Adventurer", adventurer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    int openSlots(long expeditionId) {
        return service.capacity(expeditionId).openSlots();
    }

    List<String> eventTypes(long expeditionId) {
        return jdbc.queryForList("select event_type from outbox where message_key = ? order by id", String.class,
                Long.toString(expeditionId));
    }

    <T> T lastEvent(long expeditionId, String type, Class<T> payload) {
        String message = jdbc.queryForObject(
                "select payload from outbox where message_key = ? and event_type = ? order by id desc limit 1",
                String.class, Long.toString(expeditionId), type);
        return codec.payload(codec.read(message), payload);
    }

    @Test
    void aMissedReadyCheckRevertsTheFormationAndANewcomerCompletesIt() throws Exception {
        long exp = openExpedition(1);
        long tank = hold("tank", exp, Role.TANK);
        long healer = hold("healer", exp, Role.HEALER);
        long dps1 = hold("dps-1", exp, Role.DAMAGE);

        confirm("tank", tank).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("reservation.not-in-ready-check"));

        long dps2 = hold("dps-2", exp, Role.DAMAGE);
        JsonNode assembled = reservation("dps-2", dps2);
        assertThat(assembled.at("/party/status").asString()).isEqualTo("ASSEMBLED");
        assertThat(assembled.at("/party/members")).hasSize(4);
        assertThat(openSlots(exp)).isZero();
        tryHold("late", exp, Role.DAMAGE, token(exp, "late")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("expedition.sold-out"));

        confirm("tank", tank).andExpect(jsonPath("$.status").value("CONFIRMED"));
        confirm("healer", healer).andExpect(status().isOk());
        confirm("dps-1", dps1).andExpect(status().isOk());
        confirm("tank", tank).andExpect(jsonPath("$.status").value("CONFIRMED")); // repeating is harmless

        // dps-2 never answers
        clock.advance(Duration.ofSeconds(59));
        service.sweep(exp);
        assertThat(reservation("dps-2", dps2).get("status").asString()).isEqualTo("HELD");
        clock.advance(Duration.ofSeconds(1));
        service.sweep(exp);

        assertThat(reservation("dps-2", dps2).get("status").asString()).isEqualTo("EXPIRED");
        JsonNode reverted = reservation("tank", tank);
        assertThat(reverted.get("status").asString()).isEqualTo("HELD");
        assertThat(reverted.at("/party/status").asString()).isEqualTo("FORMING");
        assertThat(Instant.parse(reverted.get("heldUntil").asString())).isEqualTo(clock.instant().plus(Duration.ofMinutes(10)));
        assertThat(openSlots(exp)).isEqualTo(1);
        PartyReverted revert = lastEvent(exp, Events.PARTY_REVERTED, PartyReverted.class);
        assertThat(revert.failed()).containsExactly("dps-2");
        assertThat(revert.kept()).containsExactly("tank", "healer", "dps-1");

        confirm("dps-2", dps2).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("reservation.closed"));

        long newcomer = hold("dps-3", exp, Role.DAMAGE);
        for (var r : Map.of("tank", tank, "healer", healer, "dps-1", dps1).entrySet()) {
            confirm(r.getKey(), r.getValue()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        }
        confirm("dps-3", newcomer).andExpect(jsonPath("$.status").value("LOCKED"))
                .andExpect(jsonPath("$.party.status").value("LOCKED"));

        PartyLocked locked = lastEvent(exp, Events.PARTY_LOCKED, PartyLocked.class);
        assertThat(locked.members()).extracting(Events.Member::adventurerId)
                .containsExactlyInAnyOrder("tank", "healer", "dps-1", "dps-3");
        mvc.perform(delete("/api/reservations/" + tank).header("X-Adventurer", "tank"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("reservation.locked"));

        assertThat(eventTypes(exp)).containsSubsequence(
                Events.CAPACITY_CHANGED, Events.RESERVATION_HELD, Events.PARTY_ASSEMBLED,
                Events.RESERVATION_CONFIRMED, Events.RESERVATION_RELEASED, Events.PARTY_REVERTED,
                Events.RESERVATION_HELD, Events.PARTY_ASSEMBLED, Events.PARTY_LOCKED);
    }

    @Test
    void decliningTheReadyCheckRevertsImmediately() throws Exception {
        long exp = openExpedition(1);
        long tank = hold("tank", exp, Role.TANK);
        hold("healer", exp, Role.HEALER);
        hold("dps-1", exp, Role.DAMAGE);
        long dps2 = hold("dps-2", exp, Role.DAMAGE);
        confirm("tank", tank).andExpect(status().isOk());

        mvc.perform(delete("/api/reservations/" + dps2).header("X-Adventurer", "dps-2"))
                .andExpect(jsonPath("$.status").value("DECLINED"));

        assertThat(reservation("tank", tank).get("status").asString()).isEqualTo("HELD");
        assertThat(reservation("tank", tank).at("/party/status").asString()).isEqualTo("FORMING");
        assertThat(openSlots(exp)).isEqualTo(1);
    }

    @Test
    void holdsInAPartyThatNeverFillsLapse() throws Exception {
        long exp = openExpedition(2);
        long tank = hold("tank", exp, Role.TANK);
        assertThat(openSlots(exp)).isEqualTo(7);

        clock.advance(Duration.ofMinutes(10));
        assertThat(service.expeditionsWithWorkDue()).contains(exp);
        service.sweep(exp);

        assertThat(reservation("tank", tank).get("status").asString()).isEqualTo("EXPIRED");
        assertThat(openSlots(exp)).isEqualTo(8);
        // and the slot can be taken again, by the same adventurer too
        hold("tank", exp, Role.TANK);
    }

    @Test
    void cancellingAFormingHoldFreesTheSlot() throws Exception {
        long exp = openExpedition(1);
        long quitter = hold("quitter", exp, Role.HEALER);
        mvc.perform(delete("/api/reservations/" + quitter).header("X-Adventurer", "quitter"))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(openSlots(exp)).isEqualTo(4);
        mvc.perform(get("/api/reservations").header("X-Adventurer", "quitter")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void fillsTheFullestPartyFirst() throws Exception {
        long exp = openExpedition(2);
        long a = hold("a", exp, Role.TANK);
        long b = hold("b", exp, Role.HEALER);
        long c = hold("c", exp, Role.TANK);
        assertThat(reservation("a", a).at("/party/number").asInt()).isEqualTo(1);
        assertThat(reservation("b", b).at("/party/number").asInt()).isEqualTo(1);
        assertThat(reservation("c", c).at("/party/number").asInt()).isEqualTo(2);
        tryHold("d", exp, Role.TANK, token(exp, "d")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("reservation.role-full"));
    }

    @Test
    void admissionRules() throws Exception {
        long exp = openExpedition(1);
        tryHold("mira", exp, Role.TANK, token(exp, "teo")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("admission.invalid"));
        tryHold("mira", exp, Role.TANK, token(exp + 1000, "mira")).andExpect(status().isForbidden());
        String stale = tokens.issue(new Admission(exp, "mira", clock.instant()));
        tryHold("mira", exp, Role.TANK, stale).andExpect(status().isForbidden());
        tryHold("mira", exp, Role.TANK, "not-a-token").andExpect(status().isForbidden());

        hold("mira", exp, Role.TANK);
        tryHold("mira", exp, Role.HEALER, token(exp, "mira")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("reservation.exists"));

        long later = expedition(1, PARTY, clock.instant().plusSeconds(3600));
        tryHold("mira", later, Role.TANK, token(later, "mira")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("expedition.not-open"));
    }

    @Test
    void reservationsAreOnlyVisibleToTheirOwner() throws Exception {
        long exp = openExpedition(1);
        long tank = hold("tank", exp, Role.TANK);
        mvc.perform(get("/api/reservations/" + tank).header("X-Adventurer", "someone-else"))
                .andExpect(status().isNotFound());
        confirm("someone-else", tank).andExpect(status().isNotFound());
    }

    @Test
    void registeringTwiceIsHarmless() {
        long exp = openExpedition(3);
        service.register(new ExpeditionPublished(exp, "again", "again", "sky-peak", "STAR",
                clock.instant(), 99, Map.of(Role.TANK, 4)));
        assertThat(service.capacity(exp).openSlots()).isEqualTo(12);
        assertThat(service.capacity(exp).parties()).containsEntry(Party.Status.FORMING, 3L);
    }
}
