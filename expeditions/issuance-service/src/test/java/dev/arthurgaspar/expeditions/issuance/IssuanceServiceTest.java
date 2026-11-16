package dev.arthurgaspar.expeditions.issuance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import dev.arthurgaspar.expeditions.platform.Role;
import dev.arthurgaspar.expeditions.platform.events.EventCodec;
import dev.arthurgaspar.expeditions.platform.events.EventEnvelope;
import dev.arthurgaspar.expeditions.platform.events.Events;
import dev.arthurgaspar.expeditions.platform.events.Events.CertificateIssued;
import dev.arthurgaspar.expeditions.platform.events.Events.Member;
import dev.arthurgaspar.expeditions.platform.events.Events.PartyReverted;
import dev.arthurgaspar.expeditions.platform.events.Events.RunCompleted;
import dev.arthurgaspar.expeditions.platform.events.Events.RunFailed;
import dev.arthurgaspar.expeditions.platform.events.Events.RunFailure;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IssuanceServiceTest {

    private static final AtomicLong EXPEDITIONS = new AtomicLong(300);

    @Autowired
    IssuanceService service;

    @Autowired
    EventCodec codec;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

    private static List<Member> party(String prefix) {
        return List.of(new Member(1, prefix + "-tank", Role.TANK), new Member(2, prefix + "-healer", Role.HEALER),
                new Member(3, prefix + "-dps1", Role.DAMAGE), new Member(4, prefix + "-dps2", Role.DAMAGE));
    }

    private EventEnvelope event(String type, long expeditionId, Object payload) {
        return codec.envelope(type, Long.toString(expeditionId), payload);
    }

    @Test
    void aClearedRunIssuesOneCertificatePerMemberOnce() throws Exception {
        long exp = EXPEDITIONS.incrementAndGet();
        String runId = UUID.randomUUID().toString();
        EventEnvelope completed = event(Events.RUN_COMPLETED, exp,
                new RunCompleted(runId, exp, 9, party("clr"), 20, 2403));

        service.handle(completed);
        service.handle(completed); // redelivered

        List<Integer> shares = jdbc.queryForList("select poke_share from certificates where run_id = ? order by id",
                Integer.class, runId);
        assertThat(shares).containsExactly(601, 601, 601, 600);

        List<CertificateIssued> issued = jdbc.queryForList(
                        "select payload from outbox where event_type = ? and message_key = ? order by id", String.class,
                        Events.CERTIFICATE_ISSUED, Long.toString(exp)).stream()
                .map(m -> codec.payload(codec.read(m), CertificateIssued.class)).toList();
        assertThat(issued).extracting(CertificateIssued::pokeShare).containsExactly(601, 601, 601, 600);

        mvc.perform(get("/api/certificates").header("X-Adventurer", "clr-dps2"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].pokeShare").value(600))
                .andExpect(jsonPath("$[0].role").value("DAMAGE"));
        mvc.perform(get("/api/notifications").header("X-Adventurer", "clr-tank"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].kind").value("certificate"));
    }

    @Test
    void failuresAreExplainedDifferently() throws Exception {
        long exp = EXPEDITIONS.incrementAndGet();
        service.handle(event(Events.RUN_FAILED, exp, new RunFailed("r1", exp, 1, party("fnt"), RunFailure.FAINTED,
                "Fainted on floor 12 of 20")));
        service.handle(event(Events.RUN_FAILED, exp, new RunFailed("r2", exp, 2, party("off"), RunFailure.UNAVAILABLE,
                "No answer")));

        mvc.perform(get("/api/notifications").header("X-Adventurer", "fnt-healer"))
                .andExpect(jsonPath("$[0].kind").value("fainted"))
                .andExpect(jsonPath("$[0].message").value("Your party fainted. Fainted on floor 12 of 20."));
        mvc.perform(get("/api/notifications").header("X-Adventurer", "off-healer"))
                .andExpect(jsonPath("$[0].kind").value("called-off"));
        assertThat(jdbc.queryForObject("select count(*) from certificates where expedition_id = ?", Integer.class, exp))
                .isZero();
    }

    @Test
    void aRevertTellsEachSideWhatHappened() throws Exception {
        long exp = EXPEDITIONS.incrementAndGet();
        service.handle(event(Events.PARTY_REVERTED, exp,
                new PartyReverted(exp, 3, List.of("rv-late"), List.of("rv-a", "rv-b", "rv-c"))));
        mvc.perform(get("/api/notifications").header("X-Adventurer", "rv-late"))
                .andExpect(jsonPath("$[0].kind").value("ready-check-missed"));
        mvc.perform(get("/api/notifications").header("X-Adventurer", "rv-b"))
                .andExpect(jsonPath("$[0].kind").value("party-reverted"));
    }

    @Test
    void eventsItDoesNotUseAreNotRecorded() {
        long exp = EXPEDITIONS.incrementAndGet();
        EventEnvelope held = event(Events.CAPACITY_CHANGED, exp, new Events.CapacityChanged(exp, 3, 9));
        service.handle(held);
        assertThat(jdbc.queryForObject("select count(*) from inbox where event_id = ?", Integer.class,
                held.id().toString())).isZero();
    }

    @Test
    void connectedAdventurersGetNotificationsPushed() throws Exception {
        long exp = EXPEDITIONS.incrementAndGet();
        MvcResult stream = mvc.perform(get("/api/notifications/stream").header("X-Adventurer", "sse-tank"))
                .andExpect(request().asyncStarted())
                .andReturn();

        service.handle(event(Events.PARTY_ASSEMBLED, exp,
                new Events.PartyAssembled(exp, 4, Instant.parse("2026-11-13T18:00:00Z"), party("sse"))));

        String body = stream.getResponse().getContentAsString();
        assertThat(body).contains("event:ready-check").contains("Confirm before 18:00:00 UTC");
    }
}
