package app.adera.registry.key;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PartnerKeyRepository extends JpaRepository<PartnerKey, String> {

	List<PartnerKey> findByAppIdOrderByCreatedAtDesc(UUID appId);

	long countByAppIdAndEnvironmentAndStatus(UUID appId, String environment, String status);

}
