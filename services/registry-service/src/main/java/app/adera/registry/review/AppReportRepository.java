package app.adera.registry.review;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppReportRepository extends JpaRepository<AppReport, UUID> {

	Optional<AppReport> findFirstByAppIdAndUserIdAndStatus(UUID appId, UUID userId, String status);

	List<AppReport> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

	List<AppReport> findAllByOrderByCreatedAtDesc(Pageable pageable);

}
