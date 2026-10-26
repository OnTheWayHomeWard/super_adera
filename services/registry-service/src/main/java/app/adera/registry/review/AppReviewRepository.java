package app.adera.registry.review;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppReviewRepository extends JpaRepository<AppReview, UUID> {

	Optional<AppReview> findByAppIdAndUserId(UUID appId, UUID userId);

	List<AppReview> findByAppIdAndStatusOrderByCreatedAtDesc(UUID appId, String status, Pageable pageable);

	List<AppReview> findByAppIdOrderByCreatedAtDesc(UUID appId, Pageable pageable);

}
