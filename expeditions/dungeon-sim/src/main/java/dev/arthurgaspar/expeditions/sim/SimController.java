package dev.arthurgaspar.expeditions.sim;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.arthurgaspar.expeditions.sim.Simulator.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api")
public class SimController {

    public record RunRequest(long expeditionId, long partyId, @NotEmpty List<String> members) {}

    private final Simulator simulator;

    public SimController(Simulator simulator) {
        this.simulator = simulator;
    }

    /** The {@code Idempotency-Key} identifies the run: send the same one when retrying. */
    @PostMapping("/runs")
    Result run(@RequestHeader("Idempotency-Key") @Size(min = 8, max = 64) String key, @Valid @RequestBody RunRequest r)
            throws InterruptedException {
        return simulator.run(key, r.members());
    }

    @GetMapping("/chaos")
    Chaos chaos() {
        return simulator.chaos();
    }

    @PutMapping("/chaos")
    Chaos chaos(@RequestBody Chaos next) {
        simulator.chaos(next);
        return next;
    }

    @ExceptionHandler(Simulator.Unavailable.class)
    ProblemDetail unavailable(Simulator.Unavailable e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
