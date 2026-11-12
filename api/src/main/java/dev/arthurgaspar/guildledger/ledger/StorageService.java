package dev.arthurgaspar.guildledger.ledger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.arthurgaspar.guildledger.catalog.Catalog;
import dev.arthurgaspar.guildledger.common.ApiException;
import dev.arthurgaspar.guildledger.ledger.LedgerService.Leg;
import dev.arthurgaspar.guildledger.mission.ItemStack;
import dev.arthurgaspar.guildledger.mission.Mission;
import dev.arthurgaspar.guildledger.mission.MissionRepository;
import dev.arthurgaspar.guildledger.team.EventLog;
import dev.arthurgaspar.guildledger.team.Membership;
import dev.arthurgaspar.guildledger.team.MembershipRepository;
import dev.arthurgaspar.guildledger.team.Role;
import dev.arthurgaspar.guildledger.team.TeamAccess;

/** Team storage, treasury and wallets: everything a member can move by hand. */
@Service
public class StorageService {

    public record FieldBag(long missionId, String title, List<ItemStack> items) {}

    public record StorageView(List<ItemStack> items, long storageValue, long treasury, long myWallet,
                              List<FieldBag> inTheField) {}

    public record EntryView(long id, String txn, String accountKey, String asset, long delta, long balanceAfter,
                            Reason reason, Long missionId, String memo, String actor, Instant createdAt) {}

    public record PageView<T>(List<T> items, int page, int size, long total) {}

    private final LedgerService ledger;
    private final TeamAccess access;
    private final MembershipRepository memberships;
    private final MissionRepository missions;
    private final EventLog events;
    private final Catalog catalog;

    StorageService(LedgerService ledger, TeamAccess access, MembershipRepository memberships,
                   MissionRepository missions, EventLog events, Catalog catalog) {
        this.ledger = ledger;
        this.access = access;
        this.memberships = memberships;
        this.missions = missions;
        this.events = events;
        this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    public StorageView view(long teamId, long userId) {
        Membership me = access.member(teamId, userId);
        List<ItemStack> items = stacks(ledger.holdings(teamId, Accounts.STORAGE));
        long value = items.stream().mapToLong(s -> (long) catalog.item(s.itemCode()).refValue() * s.quantity()).sum();
        List<FieldBag> field = new ArrayList<>();
        for (Mission m : missions.findByTeamIdOrderByIdDesc(teamId)) {
            if (m.getStatus() == Mission.Status.DEPARTED) {
                field.add(new FieldBag(m.getId(), m.getTitle(), stacks(ledger.holdings(teamId, Accounts.bag(m.getId())))));
            }
        }
        return new StorageView(items, value, ledger.balance(teamId, Accounts.TREASURY, Accounts.POKE),
                ledger.balance(teamId, Accounts.wallet(me.getId()), Accounts.POKE), field);
    }

    /** Any member can bring items in. */
    @Transactional
    public void depositItem(long teamId, long userId, String itemCode, int quantity, String memo) {
        Membership me = access.member(teamId, userId);
        Catalog.Item item = catalog.item(itemCode);
        positive(quantity);
        ledger.post(teamId, userId, Reason.DEPOSIT, null, memo(memo),
                Leg.transfer(Accounts.WORLD, Accounts.STORAGE, itemCode, quantity));
        events.record(teamId, userId, "storage.deposit", me.displayName() + " stored " + quantity + " × " + item.name());
    }

    @Transactional
    public void discardItem(long teamId, long userId, String itemCode, int quantity, String memo) {
        Membership me = access.atLeast(teamId, userId, Role.OFFICER);
        Catalog.Item item = catalog.item(itemCode);
        positive(quantity);
        ledger.post(teamId, userId, Reason.DISCARD, null, memo(memo),
                Leg.transfer(Accounts.STORAGE, Accounts.WORLD, itemCode, quantity));
        events.record(teamId, userId, "storage.discard", me.displayName() + " discarded " + quantity + " × " + item.name());
    }

    /** Officers record Poké that came from outside (selling loot, a gift). */
    @Transactional
    public void depositPoke(long teamId, long userId, long amount, String memo) {
        Membership me = access.atLeast(teamId, userId, Role.OFFICER);
        positive(amount);
        ledger.post(teamId, userId, Reason.DEPOSIT, null, memo(memo),
                Leg.transfer(Accounts.WORLD, Accounts.TREASURY, Accounts.POKE, amount));
        events.record(teamId, userId, "treasury.deposit", me.displayName() + " deposited " + amount + " Poké");
    }

    /** Any member can move their own wallet Poké into the treasury. */
    @Transactional
    public void contribute(long teamId, long userId, long amount, String memo) {
        Membership me = access.member(teamId, userId);
        positive(amount);
        ledger.post(teamId, userId, Reason.CONTRIBUTION, null, memo(memo),
                Leg.transfer(Accounts.wallet(me.getId()), Accounts.TREASURY, Accounts.POKE, amount));
        events.record(teamId, userId, "treasury.contribution", me.displayName() + " put " + amount + " Poké in the treasury");
    }

    @Transactional
    public void payout(long teamId, long userId, long membershipId, long amount, String memo) {
        access.atLeast(teamId, userId, Role.LEADER);
        positive(amount);
        Membership target = memberships.findById(membershipId)
                .filter(m -> m.getTeamId() == teamId && m.isActive())
                .orElseThrow(() -> ApiException.notFound("member.not-found", "No such member in this team"));
        ledger.post(teamId, userId, Reason.PAYOUT, null, memo(memo),
                Leg.transfer(Accounts.TREASURY, Accounts.wallet(target.getId()), Accounts.POKE, amount));
        events.record(teamId, userId, "treasury.payout", "Paid " + amount + " Poké to " + target.displayName());
    }

    @Transactional
    public void cashOut(long teamId, long userId, long amount) {
        Membership me = access.member(teamId, userId);
        positive(amount);
        ledger.post(teamId, userId, Reason.CASH_OUT, null, null,
                Leg.transfer(Accounts.wallet(me.getId()), Accounts.WORLD, Accounts.POKE, amount));
    }

    @Transactional(readOnly = true)
    public PageView<EntryView> entries(long teamId, long userId, LedgerService.Filter filter, int page, int size) {
        access.member(teamId, userId);
        Page<LedgerEntry> p = ledger.entries(teamId, filter, page, size);
        return new PageView<>(p.getContent().stream().map(StorageService::entry).toList(), p.getNumber(), p.getSize(),
                p.getTotalElements());
    }

    @Transactional(readOnly = true)
    public LedgerService.Reconciliation reconcile(long teamId, long userId) {
        access.member(teamId, userId);
        return ledger.reconcile(teamId);
    }

    private static EntryView entry(LedgerEntry e) {
        return new EntryView(e.getId(), e.getTxn(), e.getAccountKey(), e.getAsset(), e.getDelta(), e.getBalanceAfter(),
                e.getReason(), e.getMissionId(), e.getMemo(), e.getActor() == null ? null : e.getActor().getDisplayName(),
                e.getCreatedAt());
    }

    private List<ItemStack> stacks(Map<String, Long> holdings) {
        // catalog order, so storage reads the same way every time
        return catalog.data().items().stream()
                .filter(i -> holdings.getOrDefault(i.code(), 0L) > 0)
                .map(i -> new ItemStack(i.code(), holdings.get(i.code()).intValue()))
                .toList();
    }

    private static void positive(long amount) {
        if (amount < 1) {
            throw ApiException.invalid("amount.invalid", "Amount must be at least 1");
        }
    }

    private static String memo(String memo) {
        return memo == null || memo.isBlank() ? null : memo.strip();
    }
}
