package dev.arthurgaspar.expeditions.issuance;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface CertificateRepository extends JpaRepository<Certificate, Long> {

    List<Certificate> findByAdventurerIdOrderByIdDesc(String adventurerId);

    List<Certificate> findByRunIdOrderById(String runId);
}
