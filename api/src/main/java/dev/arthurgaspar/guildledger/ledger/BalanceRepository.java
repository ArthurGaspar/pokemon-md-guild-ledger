package dev.arthurgaspar.guildledger.ledger;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

interface BalanceRepository extends JpaRepository<Balance, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Balance b where b.teamId = :teamId and b.accountKey = :account and b.asset = :asset")
    Optional<Balance> lock(Long teamId, String account, String asset);

    Optional<Balance> findByTeamIdAndAccountKeyAndAsset(Long teamId, String accountKey, String asset);

    List<Balance> findByTeamIdAndAccountKey(Long teamId, String accountKey);

    List<Balance> findByTeamIdAndAccountKeyStartingWith(Long teamId, String prefix);

    List<Balance> findByTeamId(Long teamId);
}
