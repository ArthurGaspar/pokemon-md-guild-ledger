package dev.arthurgaspar.expeditions.processing;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.arthurgaspar.expeditions.platform.events.Events.Member;
import dev.arthurgaspar.expeditions.platform.events.Events.RunFailure;

@RestController
@RequestMapping("/api")
public class RunController {

    public record View(String id, long expeditionId, long partyId, List<Member> members, Run.Status status,
                       RunFailure failure, int attempts, String lastError, Integer floorsCleared, Integer pokeFound,
                       Instant createdAt, Instant deadline, Instant finishedAt) {}

    public record Circuit(String name, String state) {}

    private final RunService service;
    private final GuardedDungeonSim sim;

    public RunController(RunService service, GuardedDungeonSim sim) {
        this.service = service;
        this.sim = sim;
    }

    @GetMapping("/runs/{id}")
    View get(@PathVariable String id) {
        return view(service.get(id));
    }

    @GetMapping("/expeditions/{expeditionId}/parties/{partyId}/runs")
    List<View> forParty(@PathVariable long expeditionId, @PathVariable long partyId) {
        return service.forParty(expeditionId, partyId).stream().map(this::view).toList();
    }

    @GetMapping("/circuit")
    Circuit circuit() {
        return new Circuit(GuardedDungeonSim.NAME, sim.circuitState().name());
    }

    private View view(Run r) {
        return new View(r.getId(), r.getExpeditionId(), r.getPartyId(), service.members(r), r.getStatus(),
                r.getFailure(), r.getAttempts(), r.getLastError(), r.getFloorsCleared(), r.getPokeFound(),
                r.getCreatedAt(), r.getDeadline(), r.getFinishedAt());
    }
}
