package dev.arthurgaspar.expeditions.queue;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.arthurgaspar.expeditions.platform.web.Adventurer;
import dev.arthurgaspar.expeditions.queue.QueueService.Status;

@RestController
@RequestMapping("/api/expeditions/{expeditionId}/queue")
public class QueueController {

    private final QueueService service;

    public QueueController(QueueService service) {
        this.service = service;
    }

    /** Joining again keeps the original place. */
    @PostMapping
    Status join(Adventurer me, @PathVariable long expeditionId) {
        return service.join(me.id(), expeditionId);
    }

    @GetMapping
    Status status(Adventurer me, @PathVariable long expeditionId) {
        return service.status(me.id(), expeditionId);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void leave(Adventurer me, @PathVariable long expeditionId) {
        service.leave(me.id(), expeditionId);
    }
}
