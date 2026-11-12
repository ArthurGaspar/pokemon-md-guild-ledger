package dev.arthurgaspar.guildledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import dev.arthurgaspar.guildledger.auth.User;
import dev.arthurgaspar.guildledger.auth.UserRepository;
import dev.arthurgaspar.guildledger.common.ApiException;
import dev.arthurgaspar.guildledger.team.TeamService;

/** Balance rows are locked for update, so racing withdrawals can't overdraw the treasury. */
@SpringBootTest
@ActiveProfiles("test")
class ConcurrentPayoutTest {

    @Autowired
    UserRepository users;

    @Autowired
    TeamService teams;

    @Autowired
    StorageService storage;

    @Autowired
    LedgerService ledger;

    @Test
    void onlyAsManyPayoutsAsTheTreasuryCovers() throws Exception {
        User leader = users.save(new User("race_lead", "Race Lead", 447, "x", java.time.Instant.now()));
        var team = teams.create(leader.getId(), "Race Team");
        storage.depositPoke(team.id(), leader.getId(), 100, "seed money");

        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    storage.payout(team.id(), leader.getId(), team.myMembershipId(), 30, null);
                    return true;
                } catch (ApiException e) {
                    assertThat(e.code()).isEqualTo("ledger.insufficient-funds");
                    return false;
                }
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Boolean> f : results) {
            if (f.get(30, TimeUnit.SECONDS)) {
                succeeded++;
            }
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(3);
        assertThat(ledger.balance(team.id(), Accounts.TREASURY, Accounts.POKE)).isEqualTo(10);
        assertThat(ledger.reconcile(team.id()).ok()).isTrue();
    }
}
