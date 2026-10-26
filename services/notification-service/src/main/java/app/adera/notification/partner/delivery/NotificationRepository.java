package app.adera.notification.partner.delivery;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

	Optional<Notification> findByAppIdAndEnvironmentAndExternalId(UUID appId, String environment, String externalId);

	List<Notification> findByAppIdOrderByCreatedAtDesc(UUID appId, Pageable pageable);

	List<Notification> findByAppIdAndStatusOrderByCreatedAtDesc(UUID appId, String status, Pageable pageable);

}
