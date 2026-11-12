package dev.arthurgaspar.guildledger.ledger;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.arthurgaspar.guildledger.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/teams/{teamId}")
class StorageController {

    record ItemMove(@NotBlank String itemCode, @Min(1) @Max(999) int quantity, @Size(max = 140) String memo) {}

    record PokeMove(@Min(1) @Max(1_000_000) long amount, @Size(max = 140) String memo) {}

    record Payout(@NotNull Long membershipId, @Min(1) @Max(1_000_000) long amount, @Size(max = 140) String memo) {}

    private final StorageService storage;

    StorageController(StorageService storage) {
        this.storage = storage;
    }

    @GetMapping("/storage")
    StorageService.StorageView view(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId) {
        return storage.view(teamId, CurrentUser.id(jwt));
    }

    @PostMapping("/storage/deposit")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deposit(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId, @Valid @RequestBody ItemMove req) {
        storage.depositItem(teamId, CurrentUser.id(jwt), req.itemCode(), req.quantity(), req.memo());
    }

    @PostMapping("/storage/discard")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void discard(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId, @Valid @RequestBody ItemMove req) {
        storage.discardItem(teamId, CurrentUser.id(jwt), req.itemCode(), req.quantity(), req.memo());
    }

    @PostMapping("/treasury/deposit")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void depositPoke(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId, @Valid @RequestBody PokeMove req) {
        storage.depositPoke(teamId, CurrentUser.id(jwt), req.amount(), req.memo());
    }

    @PostMapping("/treasury/contribute")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void contribute(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId, @Valid @RequestBody PokeMove req) {
        storage.contribute(teamId, CurrentUser.id(jwt), req.amount(), req.memo());
    }

    @PostMapping("/treasury/payout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void payout(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId, @Valid @RequestBody Payout req) {
        storage.payout(teamId, CurrentUser.id(jwt), req.membershipId(), req.amount(), req.memo());
    }

    @PostMapping("/wallet/cash-out")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cashOut(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId, @Valid @RequestBody PokeMove req) {
        storage.cashOut(teamId, CurrentUser.id(jwt), req.amount());
    }

    @GetMapping("/ledger")
    StorageService.PageView<StorageService.EntryView> ledger(
            @AuthenticationPrincipal Jwt jwt, @PathVariable long teamId,
            @RequestParam(required = false) String account, @RequestParam(required = false) String asset,
            @RequestParam(required = false) Reason reason, @RequestParam(required = false) Long missionId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return storage.entries(teamId, CurrentUser.id(jwt), new LedgerService.Filter(account, asset, reason, missionId),
                page, size);
    }

    @GetMapping("/ledger/reconcile")
    LedgerService.Reconciliation reconcile(@AuthenticationPrincipal Jwt jwt, @PathVariable long teamId) {
        return storage.reconcile(teamId, CurrentUser.id(jwt));
    }
}
