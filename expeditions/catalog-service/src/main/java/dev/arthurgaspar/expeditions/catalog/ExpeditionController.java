package dev.arthurgaspar.expeditions.catalog;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.arthurgaspar.expeditions.catalog.ExpeditionService.Draft;
import dev.arthurgaspar.expeditions.catalog.ExpeditionService.View;
import dev.arthurgaspar.expeditions.platform.Role;
import dev.arthurgaspar.expeditions.platform.web.Adventurer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/expeditions")
public class ExpeditionController {

    public record PublishRequest(
            @NotBlank @Pattern(regexp = "[a-z0-9-]{3,40}") String code,
            @NotBlank @Size(max = 80) String name,
            @NotBlank String dungeon,
            @NotBlank String difficulty,
            @NotNull Instant opensAt,
            @Min(1) @Max(500) int parties,
            @NotEmpty Map<Role, Integer> composition) {}

    private final ExpeditionService service;

    public ExpeditionController(ExpeditionService service) {
        this.service = service;
    }

    @GetMapping
    List<View> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    View get(@PathVariable long id) {
        return service.get(id);
    }

    /** Guild staff only once Keycloak is in (phase 3); for now any identified caller. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    View publish(Adventurer staff, @Valid @RequestBody PublishRequest r) {
        return service.publish(staff.id(), new Draft(r.code(), r.name(), r.dungeon(), r.difficulty(), r.opensAt(),
                r.parties(), r.composition()));
    }
}
