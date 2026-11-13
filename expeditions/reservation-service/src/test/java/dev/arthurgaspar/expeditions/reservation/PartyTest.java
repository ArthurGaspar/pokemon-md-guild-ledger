package dev.arthurgaspar.expeditions.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.arthurgaspar.expeditions.platform.Role;
import dev.arthurgaspar.expeditions.reservation.Reservation.Status;

class PartyTest {

    private static final Instant NOW = Instant.parse("2026-11-13T18:00:00Z");
    private static final Map<Role, Integer> COMPOSITION = Map.of(Role.TANK, 1, Role.HEALER, 1, Role.DAMAGE, 2);

    private Party assembled(Party party) {
        party.join("tank", Role.TANK, COMPOSITION, NOW, NOW.plusSeconds(600));
        party.join("healer", Role.HEALER, COMPOSITION, NOW, NOW.plusSeconds(600));
        party.join("dps-1", Role.DAMAGE, COMPOSITION, NOW, NOW.plusSeconds(600));
        party.join("dps-2", Role.DAMAGE, COMPOSITION, NOW, NOW.plusSeconds(600));
        party.startReadyCheck(NOW.plusSeconds(60));
        return party;
    }

    @Test
    void rolesFillUpToTheComposition() {
        Party party = new Party(1, 1);
        party.join("tank", Role.TANK, COMPOSITION, NOW, NOW.plusSeconds(600));
        assertThat(party.openFor(Role.TANK, COMPOSITION)).isZero();
        assertThat(party.openFor(Role.DAMAGE, COMPOSITION)).isEqualTo(2);
        assertThatThrownBy(() -> party.join("tank-2", Role.TANK, COMPOSITION, NOW, NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(party.isFull(COMPOSITION)).isFalse();
    }

    @Test
    void locksOnlyWhenEveryoneConfirmed() {
        Party party = assembled(new Party(1, 1));
        assertThatThrownBy(() -> party.join("late", Role.DAMAGE, COMPOSITION, NOW, NOW)).isInstanceOf(IllegalStateException.class);
        party.members().subList(0, 3).forEach(Reservation::confirm);
        assertThat(party.everyoneConfirmed()).isFalse();
        assertThatThrownBy(() -> party.lock(NOW)).isInstanceOf(IllegalStateException.class);

        party.members().get(3).confirm();
        party.lock(NOW);
        assertThat(party.getStatus()).isEqualTo(Party.Status.LOCKED);
        assertThat(party.members()).extracting(Reservation::getStatus).containsOnly(Status.LOCKED);
    }

    @Test
    void revertKeepsTheConfirmedMembersWithAFreshHold() {
        Party party = assembled(new Party(1, 1));
        party.members().subList(0, 3).forEach(Reservation::confirm);
        Reservation missing = party.members().get(3);
        missing.close(Status.EXPIRED, NOW.plusSeconds(60));

        Instant freshHold = NOW.plus(Duration.ofMinutes(11));
        var kept = party.revert(freshHold);

        assertThat(kept).extracting(Reservation::getAdventurerId).containsExactly("tank", "healer", "dps-1");
        assertThat(kept).allSatisfy(r -> {
            assertThat(r.getStatus()).isEqualTo(Status.HELD);
            assertThat(r.getHeldUntil()).isEqualTo(freshHold);
        });
        assertThat(party.getStatus()).isEqualTo(Party.Status.FORMING);
        assertThat(party.getReadyBy()).isNull();
        assertThat(party.openFor(Role.DAMAGE, COMPOSITION)).isEqualTo(1);
    }

    @Test
    void readyCheckDeadlineIsExclusive() {
        Party party = assembled(new Party(1, 1));
        assertThat(party.readyCheckMissed(NOW.plusSeconds(59))).isFalse();
        assertThat(party.readyCheckMissed(NOW.plusSeconds(60))).isTrue();
    }

    @Test
    void lockedReservationsCannotBeClosed() {
        Party party = assembled(new Party(1, 1));
        party.members().forEach(Reservation::confirm);
        party.lock(NOW);
        assertThatThrownBy(() -> party.members().getFirst().close(Status.CANCELLED, NOW))
                .isInstanceOf(IllegalStateException.class);
    }
}
