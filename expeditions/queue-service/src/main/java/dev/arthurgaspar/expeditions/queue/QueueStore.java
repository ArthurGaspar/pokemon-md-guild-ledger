package dev.arthurgaspar.expeditions.queue;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * The line in Redis. Each operation is one Lua script, so it is atomic without locks. An expedition's keys share
 * the {@code {e:id}} hash tag, so they live on one node if this ever runs on a Redis cluster.
 */
@Component
public class QueueStore {

    /** What position.lua returns. */
    public record Position(int state, long a, long b, long openSlots, long activeAdmissions) {
        static final int UNKNOWN = -1;
        static final int WAITING = 0;
        static final int ADMITTED = 1;
        static final int NOT_IN_LINE = 2;
    }

    static final String OPEN_EXPEDITIONS = "queue:expeditions";

    private final StringRedisTemplate redis;
    private final RedisScript<List> position = script("position", List.class);
    private final RedisScript<List> admit = script("admit", List.class);
    private final RedisScript<Long> capacity = script("capacity", Long.class);
    private final RedisScript<Long> register = script("register", Long.class);

    public QueueStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static <T> RedisScript<T> script(String name, Class<T> result) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/" + name + ".lua"));
        script.setResultType(result);
        return script;
    }

    static String info(long id) {
        return "queue:{e:" + id + "}:info";
    }

    static String line(long id) {
        return "queue:{e:" + id + "}:line";
    }

    static String seq(long id) {
        return "queue:{e:" + id + "}:seq";
    }

    static String admitted(long id) {
        return "queue:{e:" + id + "}:admitted";
    }

    public Position join(long expeditionId, String adventurerId, Instant now) {
        return position(expeditionId, adventurerId, now, "join");
    }

    public Position peek(long expeditionId, String adventurerId, Instant now) {
        return position(expeditionId, adventurerId, now, "peek");
    }

    private Position position(long id, String adventurerId, Instant now, String mode) {
        @SuppressWarnings("unchecked")
        List<Long> r = redis.execute(position, List.of(info(id), line(id), seq(id), admitted(id)),
                adventurerId, Long.toString(now.toEpochMilli()), mode);
        return new Position(r.get(0).intValue(), r.get(1), r.get(2), r.get(3), r.get(4));
    }

    public boolean leave(long expeditionId, String adventurerId) {
        Long removed = redis.opsForZSet().remove(line(expeditionId), adventurerId);
        return removed != null && removed > 0;
    }

    @SuppressWarnings("unchecked")
    public List<String> admit(long expeditionId, Instant now, int batch, long admissionTtlMillis) {
        return redis.execute(admit, List.of(info(expeditionId), line(expeditionId), admitted(expeditionId)),
                Long.toString(now.toEpochMilli()), Integer.toString(batch), Long.toString(admissionTtlMillis));
    }

    public void register(long expeditionId, Instant opensAt, int totalSlots) {
        redis.execute(register, List.of(info(expeditionId)), Long.toString(opensAt.toEpochMilli()),
                Integer.toString(totalSlots));
        redis.opsForSet().add(OPEN_EXPEDITIONS, Long.toString(expeditionId));
    }

    /** @return whether the update was newer than the stored one */
    public boolean capacity(long expeditionId, int openSlots, long version) {
        Long applied = redis.execute(capacity, List.of(info(expeditionId)), Integer.toString(openSlots),
                Long.toString(version));
        return applied != null && applied == 1;
    }

    /** The admission was used: the adventurer holds a slot now, which frees room for the next in line. */
    public void admissionUsed(long expeditionId, String adventurerId) {
        redis.opsForZSet().remove(admitted(expeditionId), adventurerId);
    }

    public Set<Long> expeditions() {
        Set<String> ids = redis.opsForSet().members(OPEN_EXPEDITIONS);
        return ids == null ? Set.of() : ids.stream().map(Long::valueOf).collect(Collectors.toSet());
    }

    public Instant opensAt(long expeditionId) {
        Object v = redis.opsForHash().get(info(expeditionId), "opensAt");
        return v == null ? null : Instant.ofEpochMilli(Long.parseLong(v.toString()));
    }
}
