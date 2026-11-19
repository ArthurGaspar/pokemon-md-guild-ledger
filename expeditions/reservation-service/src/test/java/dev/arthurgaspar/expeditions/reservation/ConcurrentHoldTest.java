package dev.arthurgaspar.expeditions.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import dev.arthurgaspar.expeditions.platform.Role;
import dev.arthurgaspar.expeditions.platform.admission.AdmissionTokens;
import dev.arthurgaspar.expeditions.platform.admission.AdmissionTokens.Admission;
import dev.arthurgaspar.expeditions.platform.events.EventCodec;
import dev.arthurgaspar.expeditions.platform.events.Events;
import dev.arthurgaspar.expeditions.platform.events.Events.CapacityChanged;
import dev.arthurgaspar.expeditions.platform.events.Events.ExpeditionPublished;
import dev.arthurgaspar.expeditions.platform.web.ApiException;

/**
 * Forty adventurers race for six damage slots. Exactly six get one, no party goes over its composition, and the
 * capacity events come out of the outbox with strictly increasing versions (the queue relies on that).
 */
@SpringBootTest
@ActiveProfiles("test")
class ConcurrentHoldTest {

    @Autowired
    ReservationService service;

    @Autowired
    AdmissionTokens tokens;

    @Autowired
    EventCodec codec;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Clock clock;

    @Test
    void sixSlotsFortyAdventurers() throws Exception {
        long exp = 9_001;
        service.register(new ExpeditionPublished(exp, "race", "Race", "sky-peak", "STAR", clock.instant().minusSeconds(1),
                3, Map.of(Role.TANK, 1, Role.DAMAGE, 2)));

        List<Callable<Boolean>> attempts = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String adventurer = "racer-" + i;
            String token = tokens.issue(new Admission(exp, adventurer, clock.instant().plusSeconds(60)));
            attempts.add(() -> {
                try {
                    service.hold(adventurer, exp, Role.DAMAGE, token);
                    return true;
                } catch (ApiException e) {
                    assertThat(e.code()).isEqualTo("reservation.role-full");
                    return false;
                }
            });
        }
        int won = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (Future<Boolean> f : pool.invokeAll(attempts)) {
                won += f.get() ? 1 : 0;
            }
        }

        assertThat(won).isEqualTo(6);
        assertThat(service.capacity(exp).openSlots()).isEqualTo(3);
        List<Integer> damagePerParty = jdbc.queryForList("""
                select count(*) from reservations where expedition_id = ? and role = 'DAMAGE' and status = 'HELD'
                group by party_id""", Integer.class, exp);
        assertThat(damagePerParty).containsExactly(2, 2, 2);

        List<Long> versions = jdbc.queryForList(
                        "select payload from outbox where message_key = ? and event_type = ? order by id", String.class,
                        Long.toString(exp), Events.CAPACITY_CHANGED).stream()
                .map(m -> codec.payload(codec.read(m), CapacityChanged.class).version())
                .toList();
        assertThat(versions).hasSize(7).isSorted().doesNotHaveDuplicates();
    }
}
