package dev.arthurgaspar.expeditions.reservation;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.arthurgaspar.expeditions.platform.Role;
import dev.arthurgaspar.expeditions.platform.web.Adventurer;
import dev.arthurgaspar.expeditions.reservation.ReservationService.Capacity;
import dev.arthurgaspar.expeditions.reservation.ReservationService.View;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api")
public class ReservationController {

    public record HoldRequest(@NotNull Role role, @NotBlank String admissionToken) {}

    private final ReservationService service;

    public ReservationController(ReservationService service) {
        this.service = service;
    }

    @PostMapping("/expeditions/{expeditionId}/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    View hold(Adventurer me, @PathVariable long expeditionId, @Valid @RequestBody HoldRequest r) {
        return service.hold(me.id(), expeditionId, r.role(), r.admissionToken());
    }

    @GetMapping("/expeditions/{expeditionId}/capacity")
    Capacity capacity(@PathVariable long expeditionId) {
        return service.capacity(expeditionId);
    }

    @GetMapping("/reservations")
    List<View> mine(Adventurer me) {
        return service.mine(me.id());
    }

    @GetMapping("/reservations/{id}")
    View get(Adventurer me, @PathVariable long id) {
        return service.get(me.id(), id);
    }

    @PostMapping("/reservations/{id}/confirm")
    View confirm(Adventurer me, @PathVariable long id) {
        return service.confirm(me.id(), id);
    }

    @DeleteMapping("/reservations/{id}")
    View cancel(Adventurer me, @PathVariable long id) {
        return service.cancel(me.id(), id);
    }
}
