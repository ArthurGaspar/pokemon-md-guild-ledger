package dev.arthurgaspar.expeditions.issuance;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByAdventurerIdAndIdGreaterThanOrderByIdDesc(String adventurerId, long after, Limit limit);
}
