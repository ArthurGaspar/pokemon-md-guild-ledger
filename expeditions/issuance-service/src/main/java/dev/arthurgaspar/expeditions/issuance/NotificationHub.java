package dev.arthurgaspar.expeditions.issuance;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pushes notifications to adventurers connected over SSE, after the transaction that saved them commits. Connections
 * live in this instance only: with several instances, a client misses pushes from the others, and catches up through
 * {@code GET /api/notifications?after=}. Fan-out across instances (Redis pub/sub) is left for when it's needed.
 */
@Component
public class NotificationHub {

    public record Saved(Notification notification) {}

    public record View(long id, String kind, String message, long expeditionId, java.time.Instant createdAt) {
        static View of(Notification n) {
            return new View(n.getId(), n.getKind(), n.getMessage(), n.getExpeditionId(), n.getCreatedAt());
        }
    }

    private static final Duration TIMEOUT = Duration.ofMinutes(30);

    private final Map<String, List<SseEmitter>> listeners = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String adventurerId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        List<SseEmitter> mine = listeners.computeIfAbsent(adventurerId, id -> new CopyOnWriteArrayList<>());
        mine.add(emitter);
        Runnable remove = () -> mine.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    @TransactionalEventListener
    void push(Saved saved) {
        Notification n = saved.notification();
        for (SseEmitter emitter : listeners.getOrDefault(n.getAdventurerId(), List.of())) {
            try {
                emitter.send(SseEmitter.event().id(n.getId().toString()).name(n.getKind()).data(View.of(n)));
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);
            }
        }
    }

    int connections(String adventurerId) {
        return listeners.getOrDefault(adventurerId, List.of()).size();
    }
}
