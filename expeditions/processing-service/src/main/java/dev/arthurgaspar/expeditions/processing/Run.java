package dev.arthurgaspar.expeditions.processing;

import java.time.Duration;
import java.time.Instant;

import dev.arthurgaspar.expeditions.platform.events.Events.RunFailure;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A party's trip through the simulator: {@code PENDING} until it gets an answer or runs out of time. Failed calls
 * are retried on a growing delay until {@code deadline}.
 */
@Entity
@Table(name = "runs")
public class Run {

    public enum Status { PENDING, COMPLETED, FAILED }

    @Id
    private String id;

    @Column(name = "expedition_id", nullable = false)
    private Long expeditionId;

    @Column(name = "party_id", nullable = false)
    private Long partyId;

    /** {@code List<Member>} as JSON, passed on unchanged in the outcome event. */
    @Column(nullable = false)
    private String members;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    /** A worker is on it until then; past it, another worker may take the run over. */
    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(nullable = false)
    private Instant deadline;

    @Column(name = "last_error")
    private String lastError;

    @Enumerated(EnumType.STRING)
    private RunFailure failure;

    @Column(name = "floors_cleared")
    private Integer floorsCleared;

    @Column(name = "poke_found")
    private Integer pokeFound;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** The trace of the party.locked that started the run; attempts continue it. */
    @Column(name = "trace_parent")
    private String traceParent;

    @Version
    private long version;

    protected Run() {
    }

    public Run(String id, long expeditionId, long partyId, String members, Instant now, Instant deadline,
               String traceParent) {
        this.traceParent = traceParent;
        this.id = id;
        this.expeditionId = expeditionId;
        this.partyId = partyId;
        this.members = members;
        this.status = Status.PENDING;
        this.nextAttemptAt = now;
        this.deadline = deadline;
        this.createdAt = now;
    }

    void cleared(int floors, int poke, Instant now) {
        finish(Status.COMPLETED, null, now);
        this.floorsCleared = floors;
        this.pokeFound = poke;
    }

    void fainted(int floors, int poke, Instant now) {
        finish(Status.FAILED, RunFailure.FAINTED, now);
        this.floorsCleared = floors;
        this.pokeFound = poke;
    }

    /** Records a failed attempt. Returns false once the deadline has passed, when the caller gives up on the run. */
    boolean attemptFailed(String error, Instant now, Duration retryIn) {
        attempts++;
        lastError = error.length() > 300 ? error.substring(0, 300) : error;
        leaseUntil = null;
        if (!now.isBefore(deadline)) {
            return false;
        }
        nextAttemptAt = now.plus(retryIn);
        return true;
    }

    void unavailable(Instant now) {
        finish(Status.FAILED, RunFailure.UNAVAILABLE, now);
    }

    private void finish(Status outcome, RunFailure reason, Instant now) {
        if (status != Status.PENDING) {
            throw new IllegalStateException("Run " + id + " already " + status);
        }
        status = outcome;
        failure = reason;
        finishedAt = now;
        leaseUntil = null;
    }

    public String getId() {
        return id;
    }

    public Long getExpeditionId() {
        return expeditionId;
    }

    public Long getPartyId() {
        return partyId;
    }

    public String getMembers() {
        return members;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getDeadline() {
        return deadline;
    }

    public String getLastError() {
        return lastError;
    }

    public RunFailure getFailure() {
        return failure;
    }

    public Integer getFloorsCleared() {
        return floorsCleared;
    }

    public Integer getPokeFound() {
        return pokeFound;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getTraceParent() {
        return traceParent;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
