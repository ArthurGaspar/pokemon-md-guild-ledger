package dev.arthurgaspar.expeditions.issuance;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.arthurgaspar.expeditions.platform.events.EventCodec;
import dev.arthurgaspar.expeditions.platform.events.EventEnvelope;
import dev.arthurgaspar.expeditions.platform.events.Events;
import dev.arthurgaspar.expeditions.platform.events.Events.CertificateIssued;
import dev.arthurgaspar.expeditions.platform.events.Events.Member;
import dev.arthurgaspar.expeditions.platform.events.Events.PartyAssembled;
import dev.arthurgaspar.expeditions.platform.events.Events.PartyLocked;
import dev.arthurgaspar.expeditions.platform.events.Events.PartyReverted;
import dev.arthurgaspar.expeditions.platform.events.Events.RunCompleted;
import dev.arthurgaspar.expeditions.platform.events.Events.RunFailed;
import dev.arthurgaspar.expeditions.platform.events.Events.RunFailure;
import dev.arthurgaspar.expeditions.platform.events.Topics;
import dev.arthurgaspar.expeditions.platform.outbox.Inbox;
import dev.arthurgaspar.expeditions.platform.outbox.Outbox;

/**
 * The saga's last step: certificates and loot shares for a cleared run, and a notification for every moment an
 * adventurer should hear about. Each event is handled once (the inbox), in one transaction.
 */
@Service
public class IssuanceService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private final CertificateRepository certificates;
    private final NotificationRepository notifications;
    private final Inbox inbox;
    private final Outbox outbox;
    private final EventCodec codec;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public IssuanceService(CertificateRepository certificates, NotificationRepository notifications, Inbox inbox,
                           Outbox outbox, EventCodec codec, ApplicationEventPublisher events, Clock clock) {
        this.certificates = certificates;
        this.notifications = notifications;
        this.inbox = inbox;
        this.outbox = outbox;
        this.codec = codec;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void handle(EventEnvelope envelope) {
        if (!isRelevant(envelope.type()) || !inbox.firstDelivery(envelope)) {
            return;
        }
        switch (envelope.type()) {
            case Events.RUN_COMPLETED -> issue(codec.payload(envelope, RunCompleted.class));
            case Events.RUN_FAILED -> runFailed(codec.payload(envelope, RunFailed.class));
            case Events.PARTY_ASSEMBLED -> {
                PartyAssembled e = codec.payload(envelope, PartyAssembled.class);
                for (Member m : e.members()) {
                    notify(m.adventurerId(), "ready-check", e.expeditionId(),
                            "Your party is complete! Confirm before " + TIME.format(e.readyBy()) + ".");
                }
            }
            case Events.PARTY_REVERTED -> {
                PartyReverted e = codec.payload(envelope, PartyReverted.class);
                for (String a : e.failed()) {
                    notify(a, "ready-check-missed", e.expeditionId(),
                            "You didn't confirm in time, so your place was released.");
                }
                for (String a : e.kept()) {
                    notify(a, "party-reverted", e.expeditionId(),
                            "Someone missed the ready check. You keep your place while the party finds a replacement.");
                }
            }
            case Events.PARTY_LOCKED -> {
                PartyLocked e = codec.payload(envelope, PartyLocked.class);
                for (Member m : e.members()) {
                    notify(m.adventurerId(), "set-out", e.expeditionId(), "Everyone's ready. Your party set out!");
                }
            }
            default -> throw new IllegalStateException(envelope.type());
        }
    }

    private static boolean isRelevant(String type) {
        return switch (type) {
            case Events.RUN_COMPLETED, Events.RUN_FAILED, Events.PARTY_ASSEMBLED, Events.PARTY_REVERTED,
                 Events.PARTY_LOCKED -> true;
            default -> false;
        };
    }

    private void issue(RunCompleted run) {
        int[] shares = LootSplit.split(run.pokeFound(), run.members().size());
        for (int i = 0; i < shares.length; i++) {
            Member m = run.members().get(i);
            Certificate c = certificates.save(new Certificate(run.runId(), run.expeditionId(), run.partyId(),
                    m.adventurerId(), m.role(), run.floorsCleared(), shares[i], clock.instant()));
            outbox.append(Topics.ISSUANCE, Long.toString(run.expeditionId()), Events.CERTIFICATE_ISSUED,
                    new CertificateIssued(c.getId(), run.runId(), run.expeditionId(), m.adventurerId(), m.role(), shares[i]));
            notify(m.adventurerId(), "certificate", run.expeditionId(), "Expedition cleared: " + run.floorsCleared()
                    + " floors. Your share is " + shares[i] + " Poké (certificate #" + c.getId() + ").");
        }
    }

    private void runFailed(RunFailed run) {
        String message = run.reason() == RunFailure.FAINTED
                ? "Your party fainted. " + run.detail() + "."
                : "The dungeon couldn't be reached, so the expedition was called off and your place released. "
                  + "Join the queue to try again.";
        for (Member m : run.members()) {
            notify(m.adventurerId(), run.reason() == RunFailure.FAINTED ? "fainted" : "called-off", run.expeditionId(),
                    message);
        }
    }

    private void notify(String adventurerId, String kind, long expeditionId, String message) {
        Notification n = notifications.save(new Notification(adventurerId, kind, message, expeditionId, clock.instant()));
        events.publishEvent(new NotificationHub.Saved(n));
    }

    @Transactional(readOnly = true)
    public List<Certificate> certificates(String adventurerId) {
        return certificates.findByAdventurerIdOrderByIdDesc(adventurerId);
    }

    @Transactional(readOnly = true)
    public List<Notification> notifications(String adventurerId, long after) {
        return notifications.findByAdventurerIdAndIdGreaterThanOrderByIdDesc(adventurerId, after, Limit.of(50));
    }
}
