package dev.arthurgaspar.expeditions.processing;

import java.util.List;

/** The simulator, as processing sees it. Any exception means "no answer"; FAINTED is an answer. */
public interface DungeonSim {

    enum Outcome { CLEARED, FAINTED }

    record Result(String runId, Outcome outcome, int floors, int floorsCleared, int pokeFound) {}

    /** @param runId sent as the idempotency key, so a retried call gets the first call's answer */
    Result run(String runId, long expeditionId, long partyId, List<String> members);
}
