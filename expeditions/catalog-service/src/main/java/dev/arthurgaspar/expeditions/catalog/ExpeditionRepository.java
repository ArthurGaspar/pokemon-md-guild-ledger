package dev.arthurgaspar.expeditions.catalog;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExpeditionRepository extends JpaRepository<Expedition, Long> {

    boolean existsByCode(String code);

    List<Expedition> findAllByOrderByOpensAtAsc();
}
