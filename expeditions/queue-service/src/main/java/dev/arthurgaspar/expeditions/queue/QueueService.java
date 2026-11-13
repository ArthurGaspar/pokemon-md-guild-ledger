package dev.arthurgaspar.expeditions.queue;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;

import dev.arthurgaspar.expeditions.platform.admission.AdmissionTokens;
import dev.arthurgaspar.expeditions.platform.admission.AdmissionTokens.Admission;
import dev.arthurgaspar.expeditions.platform.web.ApiException;
import dev.arthurgaspar.expeditions.queue.QueueStore.Position;

@Service
public class QueueService {

    public enum State { WAITING, ADMITTED, NOT_IN_LINE }

    /**
     * @param position 1-based place in line while waiting
     * @param soldOut  no slot is open now; waiting adventurers keep their place in case one is released
     * @param admissionToken present once admitted: hand it to reservation before {@code admittedUntil}
     */
    public record Status(State state, Long position, Long waiting, boolean soldOut, Instant opensAt,
                         String admissionToken, Instant admittedUntil) {}

    private final QueueStore store;
    private final AdmissionTokens tokens;
    private final Clock clock;

    public QueueService(QueueStore store, AdmissionTokens tokens, Clock clock) {
        this.store = store;
        this.tokens = tokens;
        this.clock = clock;
    }

    public Status join(String adventurerId, long expeditionId) {
        return status(expeditionId, adventurerId, store.join(expeditionId, adventurerId, clock.instant()));
    }

    public Status status(String adventurerId, long expeditionId) {
        return status(expeditionId, adventurerId, store.peek(expeditionId, adventurerId, clock.instant()));
    }

    public void leave(String adventurerId, long expeditionId) {
        store.leave(expeditionId, adventurerId);
    }

    private Status status(long expeditionId, String adventurerId, Position p) {
        boolean soldOut = p.openSlots() <= 0;
        return switch (p.state()) {
            case Position.UNKNOWN -> throw ApiException.notFound("expedition.not-found", "Expedition not found");
            case Position.WAITING -> new Status(State.WAITING, p.a() + 1, p.b(), soldOut,
                    store.opensAt(expeditionId), null, null);
            case Position.ADMITTED -> {
                Instant until = Instant.ofEpochMilli(p.a());
                String token = tokens.issue(new Admission(expeditionId, adventurerId, until));
                yield new Status(State.ADMITTED, null, null, soldOut, store.opensAt(expeditionId), token, until);
            }
            default -> new Status(State.NOT_IN_LINE, null, null, soldOut, store.opensAt(expeditionId), null, null);
        };
    }
}
