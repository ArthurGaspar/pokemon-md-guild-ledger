package dev.arthurgaspar.guildledger.ledger;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long>, JpaSpecificationExecutor<LedgerEntry> {

    List<LedgerEntry> findByTeamIdOrderByIdAsc(Long teamId);
}
