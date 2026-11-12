package dev.arthurgaspar.guildledger.ledger;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import dev.arthurgaspar.guildledger.auth.UserRepository;
import dev.arthurgaspar.guildledger.catalog.Catalog;
import dev.arthurgaspar.guildledger.common.ApiException;
import dev.arthurgaspar.guildledger.team.TeamRepository;

@Service
public class LedgerService {

    /** One side of a transaction. Positive delta adds to the account. */
    public record Leg(String account, String asset, long delta) {

        public static List<Leg> transfer(String from, String to, String asset, long amount) {
            return List.of(new Leg(from, asset, -amount), new Leg(to, asset, amount));
        }
    }

    public record Filter(String account, String asset, Reason reason, Long missionId) {}

    public record Reconciliation(boolean ok, int entries, int transactions, int accounts, List<String> problems) {}

    private final BalanceRepository balances;
    private final LedgerEntryRepository entries;
    private final UserRepository users;
    private final TeamRepository teams;
    private final Catalog catalog;
    private final Clock clock;

    LedgerService(BalanceRepository balances, LedgerEntryRepository entries, UserRepository users,
                  TeamRepository teams, Catalog catalog, Clock clock) {
        this.balances = balances;
        this.entries = entries;
        this.users = users;
        this.teams = teams;
        this.catalog = catalog;
        this.clock = clock;
    }

    /**
     * Applies all legs atomically or none. Legs are netted per account and asset and checked to sum to zero
     * per asset. Ledger writes are serialized per team by locking the team row, which also covers balance rows
     * that don't exist yet (two first-time deposits of the same item would otherwise race on the insert).
     *
     * @return the transaction id, or null when every leg nets to zero
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String post(long teamId, Long actorUserId, Reason reason, Long missionId, String memo, List<Leg> legs) {
        Map<String, Map<String, Long>> net = new TreeMap<>();
        Map<String, Long> perAsset = new HashMap<>();
        for (Leg leg : legs) {
            net.computeIfAbsent(leg.account(), k -> new TreeMap<>()).merge(leg.asset(), leg.delta(), Long::sum);
            perAsset.merge(leg.asset(), leg.delta(), Long::sum);
        }
        perAsset.forEach((asset, sum) -> {
            if (sum != 0) {
                throw new IllegalStateException("Unbalanced " + reason + " transaction: " + asset + " nets to " + sum);
            }
        });

        teams.lockById(teamId);
        String txn = UUID.randomUUID().toString();
        Instant now = clock.instant();
        var actor = actorUserId == null ? null : users.getReferenceById(actorUserId);
        boolean wrote = false;
        for (var account : net.entrySet()) {
            for (var assetDelta : account.getValue().entrySet()) {
                long delta = assetDelta.getValue();
                if (delta == 0) {
                    continue;
                }
                String key = account.getKey();
                String asset = assetDelta.getKey();
                Balance balance = balances.lock(teamId, key, asset)
                        .orElseGet(() -> balances.save(new Balance(teamId, key, asset)));
                if (!Accounts.WORLD.equals(key) && balance.getAmount() + delta < 0) {
                    throw shortfall(key, asset, balance.getAmount(), -delta);
                }
                balance.apply(delta);
                entries.save(new LedgerEntry(teamId, txn, key, asset, delta, balance.getAmount(), reason,
                        missionId, actor, memo, now));
                wrote = true;
            }
        }
        return wrote ? txn : null;
    }

    private ApiException shortfall(String account, String asset, long have, long need) {
        String where = describe(account);
        if (Accounts.POKE.equals(asset)) {
            return ApiException.invalid("ledger.insufficient-funds",
                    where + " has " + have + " Poké; " + need + " needed");
        }
        return ApiException.invalid("ledger.insufficient-stock",
                where + " has " + have + " × " + catalog.item(asset).name() + "; " + need + " needed");
    }

    private static String describe(String account) {
        if (account.startsWith("wallet:")) {
            return "Wallet";
        }
        if (account.startsWith("bag:")) {
            return "Mission bag";
        }
        return Character.toUpperCase(account.charAt(0)) + account.substring(1);
    }

    @Transactional(readOnly = true)
    public long balance(long teamId, String account, String asset) {
        return balances.findByTeamIdAndAccountKeyAndAsset(teamId, account, asset).map(Balance::getAmount).orElse(0L);
    }

    /** Non-zero holdings of one account, by asset, in catalog-independent alphabetical order. */
    @Transactional(readOnly = true)
    public Map<String, Long> holdings(long teamId, String account) {
        Map<String, Long> out = new TreeMap<>();
        for (Balance b : balances.findByTeamIdAndAccountKey(teamId, account)) {
            if (b.getAmount() != 0) {
                out.put(b.getAsset(), b.getAmount());
            }
        }
        return out;
    }

    /** Poké held by each wallet, keyed by membership id. */
    @Transactional(readOnly = true)
    public Map<Long, Long> wallets(long teamId) {
        Map<Long, Long> out = new HashMap<>();
        for (Balance b : balances.findByTeamIdAndAccountKeyStartingWith(teamId, "wallet:")) {
            if (Accounts.POKE.equals(b.getAsset())) {
                out.put(Long.parseLong(b.getAccountKey().substring("wallet:".length())), b.getAmount());
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Page<LedgerEntry> entries(long teamId, Filter filter, int page, int size) {
        Specification<LedgerEntry> spec = (root, q, cb) -> cb.equal(root.get("teamId"), teamId);
        if (filter.account() != null) {
            spec = spec.and((root, q, cb) -> filter.account().endsWith(":")
                    ? cb.like(root.get("accountKey"), filter.account() + "%")
                    : cb.equal(root.get("accountKey"), filter.account()));
        }
        if (filter.asset() != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("asset"), filter.asset()));
        }
        if (filter.reason() != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("reason"), filter.reason()));
        }
        if (filter.missionId() != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("missionId"), filter.missionId()));
        }
        return entries.findAll(spec, PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200),
                Sort.by(Sort.Direction.DESC, "id")));
    }

    /**
     * Replays the whole ledger and checks it against itself and the cached balances: every transaction
     * nets to zero per asset, every running balance follows from the previous one, no account other than
     * the world is negative, and the replayed totals equal the balances table.
     */
    @Transactional(readOnly = true)
    public Reconciliation reconcile(long teamId) {
        List<String> problems = new ArrayList<>();
        Map<String, Long> running = new LinkedHashMap<>();
        Map<String, Map<String, Long>> txnTotals = new LinkedHashMap<>();
        List<LedgerEntry> all = entries.findByTeamIdOrderByIdAsc(teamId);

        for (LedgerEntry e : all) {
            String key = e.getAccountKey() + " / " + e.getAsset();
            long after = running.getOrDefault(key, 0L) + e.getDelta();
            if (after != e.getBalanceAfter()) {
                problems.add("Entry #" + e.getId() + " (" + key + ") records balance " + e.getBalanceAfter()
                        + " but replay gives " + after);
            }
            running.put(key, after);
            txnTotals.computeIfAbsent(e.getTxn(), k -> new TreeMap<>()).merge(e.getAsset(), e.getDelta(), Long::sum);
        }
        txnTotals.forEach((txn, totals) -> totals.forEach((asset, sum) -> {
            if (sum != 0) {
                problems.add("Transaction " + txn.substring(0, 8) + " leaves " + asset + " off by " + sum);
            }
        }));

        Map<String, Long> cached = new HashMap<>();
        for (Balance b : balances.findByTeamId(teamId)) {
            cached.put(b.getAccountKey() + " / " + b.getAsset(), b.getAmount());
        }
        var keys = new java.util.TreeSet<String>(Comparator.naturalOrder());
        keys.addAll(running.keySet());
        keys.addAll(cached.keySet());
        int accounts = 0;
        for (String key : keys) {
            long replayed = running.getOrDefault(key, 0L);
            long stored = cached.getOrDefault(key, 0L);
            if (replayed != stored) {
                problems.add(key + ": balance table says " + stored + ", ledger says " + replayed);
            }
            if (replayed < 0 && !key.startsWith(Accounts.WORLD + " ")) {
                problems.add(key + " is negative (" + replayed + ")");
            }
            if (!key.startsWith(Accounts.WORLD + " ")) {
                accounts++;
            }
        }
        return new Reconciliation(problems.isEmpty(), all.size(), txnTotals.size(), accounts, problems);
    }
}
