package dev.arthurgaspar.expeditions.reservation;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface InventoryRepository extends JpaRepository<ExpeditionInventory, Long> {

    /** Every change to an expedition's parties goes through this lock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from ExpeditionInventory e where e.id = :id")
    Optional<ExpeditionInventory> lockById(Long id);
}
